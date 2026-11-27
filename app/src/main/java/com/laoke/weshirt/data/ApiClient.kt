package com.laoke.weshirt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String, val eventId: String, message: String) :
    IOException("$message\n${if (status == 0 && code in setOf("network_error", "network_timeout")) "连接错误" else "状态码 $status"} · 事件 $eventId")
internal data class ApiSessionFence(val origin: String, val revision: Long)

/** Bind the origin before acquiring credentials. Late responses cannot revive an old session. */
class ApiClient(private val session: SessionStore, cacheDir: File, client: OkHttpClient? = null) {
    private val guard = Any()
    @Volatile private var generation = 0L
    @Volatile var baseUrl: String = ""
        set(value) = synchronized(guard) {
            val normalized = if(value.isBlank()) "" else requireNotNull(validEndpoint(value)) { "Invalid HTTPS origin" }
            if(field.isNotBlank() && field != normalized) { generation++; session.clear(); pendingKeys.clear(); clearMedia(); onSessionInvalidated?.invoke() }
            field = normalized
        }
    var onSessionInvalidated: (() -> Unit)? = null
    internal fun sessionFence(): ApiSessionFence = synchronized(guard) { ApiSessionFence(baseUrl, generation) }
    var onMetric: ((path: String, elapsedMs: Long, status: Int) -> Unit)? = null
    private val refreshLock = Mutex()
    private fun sharedRefreshLock(origin: String) = refreshLocks.getOrPut("${session.installationId}\u0000$origin") { Mutex() }
    private val pendingKeys = ConcurrentHashMap<String, String>()
    private val mediaDir = File(cacheDir, "private-media").apply { mkdirs() }
    private val http = client ?: OkHttpClient.Builder().cache(Cache(File(cacheDir, "public-http"), 12L * 1024 * 1024)).connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    fun validEndpoint(value: String): String? = value.trim().toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null }?.toString()?.trimEnd('/')
    private fun snapshot(): Pair<String, Long> = synchronized(guard) { (validEndpoint(baseUrl) ?: throw ApiException(0, "endpoint_required", eventId(), "请先设置服务地址")) to generation }
    private fun current(origin: String, version: Long) { if(baseUrl != origin || generation != version) throw CancellationException("Service or account changed") }
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true, key: String? = null): JSONObject {
        require(path.startsWith("/api/") && !path.contains(".."))
        val (origin, version) = snapshot()
        // Protected calls queued by a previous refresh must stop locally once
        // login clears its credentials, rather than send an anonymous 401 that
        // could invalidate the new authorization currently being exchanged.
        val authorization = if(auth) accessSession(origin, version)
            ?: throw ApiException(401, "unauthorized", eventId(), "请先登录") else null
        val token = authorization?.str("accessToken")
        current(origin, version)
        val account = authorization?.optJSONObject("user")?.str("id").orEmpty()
        val signature = "$origin|$version|$account|$method|$path|${body ?: ""}"
        val mutation = method != "GET" && !path.startsWith("/api/mobile/auth/")
        val requestKey = key ?: if(mutation) pendingKeys.getOrPut(signature) { UUID.randomUUID().toString() } else null
        try {
            val result = try {
                raw(origin, path, method, body, token, requestKey).also {
                    if (authorization != null) currentAuthorization(origin, version, authorization) else current(origin, version)
                }
            } catch (problem: ApiException) {
                if (authorization == null || !problem.isAccessRejection()) throw problem
                val renewed = refreshSession(origin, version, authorization, rejected = true)
                // These task writes authenticate before committing. A verified
                // 401 can be retried once with the same idempotency key.
                if (method != "GET" && !(method == "POST" && (path == "/api/tasks" || PICKUP_PATH.matches(path)))) throw problem
                currentAuthorization(origin, version, renewed)
                raw(origin, path, method, body, renewed.str("accessToken"), requestKey)
                    .also { currentAuthorization(origin, version, renewed) }
            }
            pendingKeys.remove(signature)
            return result
        } catch (problem: ApiException) {
            if (problem.status in 400..499 && problem.status != 408 && problem.status != 429) pendingKeys.remove(signature)
            throw problem
        }
    }

    private fun ApiException.isAccessRejection() = status == 401 && code in setOf("unauthorized", "need_login", "access_token_expired")

    private suspend fun accessSession(origin: String, version: Long): JSONObject? {
        // An obsolete refresh may still be waiting on the network. Valid tokens
        // from a new login must not wait behind that previous account's mutex.
        val captured = synchronized(guard) {
            current(origin, version)
            val saved = session.read() ?: return null
            if (saved.str("origin") != origin) throw CancellationException("Session origin changed")
            if (saved.optLong("expiresAt") > System.currentTimeMillis() + 30_000) return saved
            saved
        }
        return refreshSession(origin, version, captured)
    }

    private suspend fun refreshSession(origin: String, version: Long, captured: JSONObject, rejected: Boolean = false): JSONObject =
        refreshLock.withLock {
            sharedRefreshLock(origin).withLock shared@ {
                current(origin, version)
                val saved = session.read()?.takeIf { sameLogicalSession(captured, it) }
                    ?: throw CancellationException("Session changed during refresh")
                if (saved.str("accessToken") != captured.str("accessToken")) return@shared saved
                if (saved.str("refreshToken").isBlank()) {
                    invalidateCaptured(origin, version, saved)
                    throw ApiException(401, "invalid_refresh_token", eventId(), "登录状态已过期，请重新登录")
                }
                if (!rejected && saved.optLong("expiresAt") > System.currentTimeMillis() + 30_000) return@shared saved

                // Once sent, the refresh can rotate server credentials even if
                // the screen is closed. Persist its single result before exiting.
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        val next = raw(origin, "/api/mobile/auth/refresh", "POST", JSONObject()
                            .put("installationId", session.installationId).put("refreshToken", saved.str("refreshToken")), null, null)
                        if (next.str("accessToken").isBlank() || next.str("refreshToken").isBlank()) {
                            throw ApiException(502, "invalid_refresh_response", eventId(), "服务暂未返回有效登录凭证")
                        }
                        synchronized(guard) {
                            current(origin, version)
                            val stored = sessionValue(next, origin, saved)
                            if (!session.compareAndSetCredentials(saved, stored)) throw CancellationException("Session credentials changed during refresh")
                            stored
                        }
                    } catch (problem: ApiException) {
                        if (problem.status in listOf(401, 403) && problem.code in setOf("invalid_refresh_token", "account_disabled")) {
                            invalidateCaptured(origin, version, saved)
                        }
                        throw problem
                    }
                }
            }
        }
    /** A new user authorization supersedes every in-flight request of the old session. */
    fun beginNativeLogin(origin: String = baseUrl) = synchronized(guard) {
        val configured = validEndpoint(baseUrl) ?: throw ApiException(0, "endpoint_required", eventId(), "请先设置服务地址")
        if (origin != configured) throw ApiException(409, "login_origin_changed", eventId(), "服务地址已变更，请重新登录")
        invalidate(configured, generation)
    }
    fun saveSession(value: JSONObject, origin: String = baseUrl) = saveSession(value, origin, null)

    internal fun saveSession(value: JSONObject, origin: String, expected: ApiSessionFence?) = synchronized(guard) {
        check(origin == baseUrl && (expected == null || expected == ApiSessionFence(baseUrl, generation))) {
            "Service or account changed during login"
        }
        generation++
        storeSession(value, origin)
    }
    /** The profile lives inside the encrypted credentials, never in ordinary preferences. */
    fun cachedProfile(): Profile? = synchronized(guard) { cachedSessionProfile(session.read(), baseUrl)?.let(Profile::parse) }
    fun cacheProfile(profile: JSONObject) = synchronized(guard) {
        val saved = session.read() ?: return@synchronized
        if (saved.str("origin") != baseUrl || profile.str("id").isBlank()) return@synchronized
        val bound = saved.optJSONObject("user")?.str("id").orEmpty()
        if (bound.isNotBlank() && bound != profile.str("id")) return@synchronized
        if (saved.optJSONObject("profile")?.toString() == profile.toString()) return@synchronized
        session.compareAndSet(saved, JSONObject(saved.toString()).put("profile", JSONObject(profile.toString())))
        Unit
    }
    /** Serialize cache writes with login replacement, while token refresh can rotate inside the same session. */
    internal fun cacheLaundry(expected: ApiSessionFence, owner: String, value: SavedLaundry?) = synchronized(guard) {
        if (expected != ApiSessionFence(baseUrl, generation)) return@synchronized
        val storage = session as? SecureSession ?: return@synchronized
        val nativeSessionId = savedLaundrySessionId(storage.read()) ?: return@synchronized
        storage.saveLaundry(baseUrl, owner, nativeSessionId, value)
    }
    private fun sessionValue(value: JSONObject, origin: String, previous: JSONObject? = null) =
        sessionWithProfile(value, origin, previous).put("expiresAt", System.currentTimeMillis() + value.optLong("expiresIn", 900) * 1000)
    private fun storeSession(value: JSONObject, origin: String, previous: JSONObject? = null) {
        session.save(sessionValue(value, origin, previous))
    }
    /** A response from another instance's old credentials cannot clear a rotation or a new login. */
    private fun invalidateCaptured(origin: String, version: Long, expected: JSONObject?) = synchronized(guard) {
        if (origin != baseUrl || version != generation) return@synchronized
        if (!session.compareAndSetCredentials(expected, null)) throw CancellationException("Session credentials changed before invalidation")
        generation++; pendingKeys.clear(); clearMedia(); onSessionInvalidated?.invoke()
    }
    /** Rotating credentials keeps the same server session and account. */
    private fun sameLogicalSession(expected: JSONObject, saved: JSONObject): Boolean {
        if (expected.str("origin") != saved.str("origin")) return false
        val owner = expected.optJSONObject("user")?.str("id").orEmpty()
        val savedOwner = saved.optJSONObject("user")?.str("id").orEmpty()
        if (owner != savedOwner) return false
        if (expected.str("accessToken") == saved.str("accessToken") &&
            expected.str("refreshToken") == saved.str("refreshToken")) return true
        if (owner.isBlank()) return false
        val before = expected.str("accessToken").split('.', limit = 3)
        val after = saved.str("accessToken").split('.', limit = 3)
        return before.size == 3 && after.size == 3 && before[0] == "ma1" && after[0] == "ma1" &&
            before[1].isNotBlank() && before[1] == after[1]
    }

    private fun currentAuthorization(origin: String, version: Long, expected: JSONObject) = synchronized(guard) {
        current(origin, version)
        val saved = session.read()
        if (saved == null || !sameLogicalSession(expected, saved))
            throw CancellationException("Session credentials changed during request")
    }
    private fun invalidate(origin: String, version: Long) = synchronized(guard) { if(origin == baseUrl && version == generation) { generation++; session.clear(); pendingKeys.clear(); clearMedia(); onSessionInvalidated?.invoke() } }
    suspend fun logout() = refreshLock.withLock {
        val (origin, version) = snapshot()
        sharedRefreshLock(origin).withLock {
            current(origin, version)
            val saved = session.read()
            try { saved?.let { raw(origin, "/api/mobile/auth/logout", "POST", JSONObject().put("installationId", session.installationId).put("refreshToken", it.str("refreshToken")), null, null) } }
            finally { invalidateCaptured(origin, version, saved) }
        }
    }
    private suspend fun raw(origin: String, path: String, method: String, body: JSONObject?, token: String?, key: String?): JSONObject {
        val requestId = eventId()
        val req = Request.Builder().url(origin + path).header("Accept", "application/json").header("X-Request-ID", requestId)
            .header("Cache-Control", "no-cache, no-store")
        if(!token.isNullOrEmpty()) req.header("Authorization", "Bearer $token")
        if(method != "GET") req.header("Idempotency-Key", key ?: UUID.randomUUID().toString())
        req.method(method, if(method in listOf("GET", "HEAD")) null else (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val started = System.nanoTime()
        var status = 0
        try { return execute(req.build(), requestId).also { status = 200 } }
        catch(e: ApiException) { status = e.status; throw e }
        finally { if(!path.startsWith("/api/telemetry")) onMetric?.invoke(path.substringBefore('?'), ((System.nanoTime()-started)/1_000_000).coerceIn(0,60_000),status) }
    }
    private suspend fun execute(request: Request, requestId: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r ->
                val body = r.body ?: throw IOException("empty response")
                if(body.contentLength() > MAX_RESPONSE) throw IOException("oversized response")
                val source = body.source(); source.request(MAX_RESPONSE + 1)
                if(source.buffer.size > MAX_RESPONSE) throw IOException("oversized response")
                val j = runCatching { JSONObject(body.string()) }.getOrElse { throw ApiException(r.code, "invalid_response", r.header("X-Event-ID") ?: requestId, "服务返回格式异常") }
                if(!r.isSuccessful) { val e = j.optJSONObject("error") ?: JSONObject(); throw ApiException(r.code, e.str("code", "http_error"), e.str("eventId", r.header("X-Event-ID") ?: requestId), e.str("message", "请求未完成")) }
                j
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException(0, "network_timeout", requestId, "等待服务器响应超时，请重试").apply { initCause(e) }
        } catch (e: IOException) {
            throw ApiException(0, "network_error", requestId, "连接未完成，请检查网络后重试").apply { initCause(e) }
        }
    }
    /** A single authenticated POST; partial answers are never retried or stored as final history. */
    internal suspend fun streamAssistant(body: JSONObject, onReset: suspend () -> Unit = {}, onDelta: suspend (String) -> Unit): JSONObject {
        val (origin, version) = snapshot()
        val authorization = accessSession(origin, version) ?: throw ApiException(401, "unauthorized", eventId(), "请先登录")
        currentAuthorization(origin, version, authorization)
        val trace = eventId()
        val request = Request.Builder().url(origin + "/api/assistant/chat/stream")
            .header("Authorization", "Bearer " + authorization.str("accessToken"))
            .header("Accept", "text/event-stream").header("Cache-Control", "no-cache, no-store")
            .header("X-Request-ID", trace).header("Idempotency-Key", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return withContext(Dispatchers.IO) {
            val call = http.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build().newCall(request)
            // Closing the page/account must interrupt a blocked read, not wait
            // for the provider's next token or the socket timeout.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            var status = 0
            val started = System.nanoTime()
            try {
                call.execute().use { response ->
                    status = response.code
                    currentCoroutineContext().ensureActive()
                    currentAuthorization(origin, version, authorization)
                    val streamTrace = response.header("X-Event-ID")?.takeIf { it.isNotBlank() } ?: trace
                    val payload = response.body ?: throw IOException("empty response")
                    if (!response.isSuccessful) {
                        val source = payload.source(); source.request(MAX_RESPONSE + 1)
                        if (source.buffer.size > MAX_RESPONSE) throw ApiException(502, "assistant_stream_invalid", streamTrace, "助手返回数据过大")
                        val error = runCatching { JSONObject(source.readUtf8()).optJSONObject("error") }.getOrNull()
                        throw ApiException(response.code, error?.str("code", "http_error") ?: "http_error",
                            error?.str("eventId", streamTrace) ?: streamTrace, error?.str("message", "助手请求未完成") ?: "助手请求未完成")
                    }
                    if (payload.contentType()?.type != "text" || payload.contentType()?.subtype != "event-stream")
                        throw ApiException(502, "assistant_stream_invalid", streamTrace, "助手暂未返回流式回答，请稍后重试")
                    val decoder = AssistantSseDecoder(streamTrace)
                    val source = payload.source()
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        currentAuthorization(origin, version, authorization)
                        val line = try { source.readUtf8LineStrict(AssistantSseDecoder.MAX_LINE_BYTES) }
                        catch (_: java.io.EOFException) { throw ApiException(502, "assistant_stream_interrupted", streamTrace, "回答连接已中断，请刷新聊天记录确认") }
                        when (val event = decoder.line(line)) {
                            AssistantStreamEvent.Reset -> onReset()
                            is AssistantStreamEvent.Delta -> onDelta(event.text)
                            is AssistantStreamEvent.Done -> {
                                currentCoroutineContext().ensureActive()
                                currentAuthorization(origin, version, authorization)
                                return@withContext event.result
                            }
                            null -> Unit
                        }
                    }
                    decoder.end()
                    throw ApiException(502, "assistant_stream_interrupted", streamTrace, "回答连接已中断，请刷新聊天记录确认")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (problem: ApiException) { status = problem.status; throw problem }
            catch (_: SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                throw ApiException(0, "network_timeout", trace, "回答等待超时，已显示的内容尚未确认保存，请刷新聊天记录")
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                throw ApiException(0, "network_error", trace, "回答连接已中断，已显示的内容尚未确认保存，请刷新聊天记录")
            } finally {
                cancellation.cancel(); call.cancel()
                onMetric?.invoke("/api/assistant/chat/stream", ((System.nanoTime() - started) / 1_000_000).coerceIn(0, 120_000), status)
            }
        }
    }
    suspend fun mediaToken(): String? { val (origin,version) = snapshot(); return accessSession(origin,version)?.str("accessToken") }
    /** Only a short-lived channel ticket is sent to the configured relay, never an access token. */
    fun channelSocket(config: JSONObject, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val (origin, version) = snapshot()
        val address = config.str("url")
        val url = address.replaceFirst("wss:", "https:").toHttpUrlOrNull()
        require(address.startsWith("wss://") && url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(config.str("protocol") == "weshirt.channel.v1" && config.str("ticket").length in 1..4096 && config.optLong("expiresAt") - System.currentTimeMillis() in 1..120_000)
        val guarded = object : okhttp3.WebSocketListener() {
            fun valid(socket: okhttp3.WebSocket): Boolean = try { current(origin, version); true } catch (_: CancellationException) { socket.cancel(); false }
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { if (valid(webSocket)) listener.onOpen(webSocket, response) }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { if (valid(webSocket)) listener.onMessage(webSocket, text) }
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) { if (valid(webSocket)) webSocket.cancel() }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { if (valid(webSocket)) listener.onFailure(webSocket, t, response) }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { listener.onClosed(webSocket, code, reason) }
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, null); listener.onClosing(webSocket, code, reason) }
        }
        return http.newWebSocket(Request.Builder().url(address).header("Origin", origin).header("Sec-WebSocket-Protocol", "weshirt.channel.v1").build(), guarded)
    }
    suspend fun upload(path: String, bytes: ByteArray, mime: String): JSONObject {
        require(path.startsWith("/api/messages/") && !path.contains("..") && bytes.size <= 8 * 1024 * 1024)
        val (origin,version) = snapshot()
        val authorization = accessSession(origin,version) ?: throw ApiException(401,"unauthorized",eventId(),"请先登录")
        val token = authorization.str("accessToken")
        current(origin,version)
        return execute(Request.Builder().url(origin + path).header("Authorization","Bearer $token").header("Cache-Control","no-store").header("Idempotency-Key",UUID.randomUUID().toString()).post(bytes.toRequestBody(mime.toMediaType())).build(),eventId()).also { currentAuthorization(origin,version,authorization) }
    }
    fun encoded(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
    /** Export is kept in memory until a user-selected document is written. */
    suspend fun adminCsv(path: String): String {
        require(path.substringBefore('?') in setOf("/api/admin/audit-logs.csv", "/api/admin/users.csv") && !path.contains("..") && !path.contains('#'))
        val (origin, version) = snapshot()
        val authorization = accessSession(origin, version) ?: throw ApiException(401, "unauthorized", eventId(), "请先登录")
        val token = authorization.str("accessToken")
        current(origin, version)
        val trace = eventId()
        try {
            return withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(origin + path).header("Authorization", "Bearer $token")
                    .header("Cache-Control", "no-store").header("Accept", "text/csv").header("X-Request-ID", trace).build()).execute().use { response ->
                    val body = response.body ?: throw IOException("empty response")
                    if (body.contentLength() > MAX_RESPONSE) throw IOException("oversized response")
                    val source = body.source(); source.request(MAX_RESPONSE + 1)
                    if (source.buffer.size > MAX_RESPONSE) throw IOException("oversized response")
                    val text = body.string()
                    currentAuthorization(origin, version, authorization)
                    if (!response.isSuccessful) {
                        val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                        throw ApiException(response.code, error?.str("code", "export_failed") ?: "export_failed",
                            error?.str("eventId", response.header("X-Event-ID") ?: trace) ?: trace,
                            error?.str("message", "文件导出失败") ?: "文件导出失败")
                    }
                    if (response.body?.contentType()?.subtype?.contains("csv") != true) throw ApiException(502, "invalid_export", trace, "文件导出格式异常")
                    text
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: ApiException) {
            if (problem.isAccessRejection()) refreshSession(origin, version, authorization, rejected = true)
            throw problem
        }
        catch (_: IOException) { throw ApiException(0, "export_failed", trace, "文件导出未完成，请重试") }
    }
    fun clearMedia() { mediaDir.listFiles()?.forEach { it.delete() } }
    suspend fun downloadMedia(mediaId: String, maxBytes: Long = 8L * 1024 * 1024): File {
        require(mediaId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && maxBytes in 1..8L*1024*1024)
        val (origin,version) = snapshot()
        val authorization = accessSession(origin,version) ?: throw ApiException(401,"unauthorized",eventId(),"请先登录")
        val token = authorization.str("accessToken")
        current(origin,version)
        val temp = File(mediaDir, UUID.randomUUID().toString())
        return try { withContext(Dispatchers.IO) {
            val trace = eventId()
            try {
                http.newCall(Request.Builder().url("$origin/api/messages/media/${encoded(mediaId)}").header("Authorization","Bearer $token").header("Cache-Control","no-store").build()).execute().use { response ->
                    if(!response.isSuccessful) throw ApiException(response.code,"media_unavailable",response.header("X-Event-ID") ?: trace,"附件暂不可用")
                    val body = response.body ?: throw IOException("No attachment")
                    if(body.contentLength() > maxBytes) throw IOException("Attachment too large")
                    body.byteStream().use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(16*1024); var total = 0L
                        while(true) { val count = input.read(buffer); if(count < 0) break; total += count; if(total > maxBytes) throw IOException("Attachment too large"); currentAuthorization(origin,version,authorization); output.write(buffer,0,count) }
                    } }
                }
                currentAuthorization(origin,version,authorization); temp
            } catch(e: CancellationException) { temp.delete(); throw e } catch(e: ApiException) { temp.delete(); throw e } catch(_: IOException) { temp.delete(); throw ApiException(0,"media_error",trace,"附件下载未完成") }
        } } catch (cancel: CancellationException) { temp.delete(); throw cancel }
    }
    private fun eventId() = "client_" + UUID.randomUUID().toString().replace("-", "")
    companion object {
        private val PICKUP_PATH = Regex("/api/tasks/[^/?#]+/take")
        private const val MAX_RESPONSE = 2L * 1024 * 1024
        private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    }
}
