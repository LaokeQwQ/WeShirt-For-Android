package com.laoke.weshirt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiClientTest {
    private class MemorySession : SessionStore {
        override val installationId = "native_test_installation"
        private var value: String? = null
        @Synchronized override fun read() = value?.let(::JSONObject)
        @Synchronized override fun save(value: JSONObject) { this.value = value.toString() }
        @Synchronized override fun clear() { value = null }
    }
    private lateinit var server: MockWebServer
    private lateinit var second: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var sessions: MemorySession
    private lateinit var api: ApiClient
    private val temporary = Files.createTempDirectory("weshirt-api-test").toFile()

    @Before fun prepare() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        // Keep the listening address and HTTPS URL on the same IP family. With
        // retries disabled, the deliberate disconnect penalizes that route;
        // localhost may then select an unbound IPv4/IPv6 alternative. A failure
        // before receipt is not the uncertain completed write this test needs.
        val loopback = InetAddress.getByName("127.0.0.1")
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start(loopback, 0) }
        second = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start(loopback, 0) }
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        sessions = MemorySession()
        api = ApiClient(sessions, temporary, client).apply { baseUrl = origin(server) }
    }
    @After fun finish() {
        server.shutdown(); second.shutdown()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        temporary.deleteRecursively()
    }
    private fun origin(value: MockWebServer) = value.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
    private fun expired() = sessions.save(JSONObject().put("origin", api.baseUrl).put("accessToken", "old-access").put("refreshToken", "old-refresh").put("expiresAt", 0))
    private fun valid() = sessions.save(JSONObject().put("origin", api.baseUrl).put("accessToken", "valid-access").put("refreshToken", "valid-refresh").put("expiresAt", System.currentTimeMillis() + 600_000))
    private val refreshed = """{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":900,"refreshExpiresIn":2592000}"""

    @Test fun concurrentRequestsRefreshOnceAndUseTheSameRotatedAccessToken() = runBlocking {
        expired()
        val refreshes = AtomicInteger()
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when(request.path) {
                "/api/mobile/auth/refresh" -> { refreshes.incrementAndGet(); json(refreshed).setBodyDelay(150, TimeUnit.MILLISECONDS) }
                "/api/tasks" -> { assertEquals("Bearer new-access", request.getHeader("Authorization")); reads.incrementAndGet(); json("{\"tasks\":[]}") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        (1..8).map { async(Dispatchers.Default) { api.call("/api/tasks") } }.awaitAll()
        assertEquals(1, refreshes.get()); assertEquals(8, reads.get())
        assertEquals("new-refresh", sessions.read()?.str("refreshToken"))
    }

    @Test fun changingOriginWhileRefreshIsPendingCannotReviveOrSendCredentialsToTheNewOrigin() = runBlocking {
        expired()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/mobile/auth/refresh", request.path)
                entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                return json(refreshed)
            }
        }
        supervisorScope {
            val pending = async(Dispatchers.Default) { api.call("/api/tasks") }
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            api.baseUrl = origin(second)
            release.countDown()
            try { pending.await(); fail("A stale refresh must be cancelled") } catch (_: CancellationException) { }
        }
        assertNull(sessions.read())
        assertEquals(0, second.requestCount)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertTrue(request.body.readUtf8().contains("old-refresh"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun logoutWaitsForRefreshUsesTheRotatedRefreshTokenAndAlwaysClearsTheSession() = runBlocking {
        expired()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var logoutToken = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when(request.path) {
                "/api/mobile/auth/refresh" -> { entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS)); json(refreshed) }
                "/api/mobile/auth/logout" -> { logoutToken = JSONObject(request.body.readUtf8()).str("refreshToken"); json("{\"ok\":true}") }
                "/api/tasks" -> json("{\"tasks\":[]}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        supervisorScope {
            val read = async(Dispatchers.Default) { api.call("/api/tasks") }
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            val logout = async(Dispatchers.Default) { api.logout() }
            delay(50)
            assertFalse(logout.isCompleted)
            release.countDown(); logout.await()
            try { read.await() } catch (_: CancellationException) { }
        }
        assertEquals("new-refresh", logoutToken)
        assertNull(sessions.read())
    }

    @Test fun failedLogoutStillRemovesLocalCredentials() = runBlocking {
        valid()
        server.enqueue(json("{\"error\":{\"code\":\"unavailable\",\"eventId\":\"evt_logout\",\"message\":\"暂不可用\"}}").setResponseCode(503))
        try { api.logout(); fail("Logout failure should be reported") } catch (e: ApiException) { assertEquals(503, e.status) }
        assertNull(sessions.read())
    }

    @Test fun errorsPreserveServerStatusCodeAndEventId() = runBlocking {
        server.enqueue(json("{\"error\":{\"code\":\"forbidden\",\"message\":\"没有权限\",\"eventId\":\"evt_server_trace\",\"status\":403}}").setResponseCode(403).setHeader("X-Event-ID", "evt_header"))
        try { api.call("/api/example", auth = false); fail("Expected API error") }
        catch(e: ApiException) { assertEquals(403, e.status); assertEquals("forbidden", e.code); assertEquals("evt_server_trace", e.eventId); assertTrue(e.message.orEmpty().contains("403")) }
        server.enqueue(MockResponse().setResponseCode(502).setHeader("X-Event-ID", "evt_non_json").setBody("invalid"))
        try { api.call("/api/example", auth = false); fail("Expected invalid response error") }
        catch(e: ApiException) { assertEquals(502, e.status); assertEquals("invalid_response", e.code); assertEquals("evt_non_json", e.eventId) }
    }

    @Test fun uncertainWriteRetryKeepsIdempotencyKeyAndNextSuccessfulOperationGetsANewOne() = runBlocking {
        valid()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json("{\"ok\":true}")); server.enqueue(json("{\"ok\":true}"))
        val body = JSONObject().put("machineId", "machine_one").put("minutes", 40)
        try { api.call("/api/tasks", "POST", body); fail("Expected uncertain network failure") }
        catch(e: ApiException) { assertEquals("network_error", e.code); assertEquals(0, e.status); assertTrue(e.eventId.startsWith("client_")) }
        val accepted = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull("The failed call must have reached the server before it disconnected", accepted)
        assertEquals("POST", accepted!!.method); assertEquals("/api/tasks", accepted.path)
        val acceptedBody = accepted.body.readUtf8()
        assertEquals(body.toString(), acceptedBody)
        val first = accepted.getHeader("Idempotency-Key")
        assertFalse(first.isNullOrBlank())
        assertTrue(api.call("/api/tasks", "POST", body).getBoolean("ok"))
        assertTrue(api.call("/api/tasks", "POST", body).getBoolean("ok"))
        val retry = server.takeRequest(3, TimeUnit.SECONDS)!!
        val next = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals(acceptedBody, retry.body.readUtf8()); assertEquals(acceptedBody, next.body.readUtf8())
        assertEquals(first, retry.getHeader("Idempotency-Key"))
        assertNotEquals(first, next.getHeader("Idempotency-Key"))
        assertEquals(3, server.requestCount)
    }

    @Test fun unsafeEndpointsAreRejectedAndHttpsOriginIsNormalized() {
        listOf("", "http://example.org", "https://user:password@example.org", "https://example.org/path", "https://example.org?token=x", "https://example.org/#fragment", "javascript:alert(1)").forEach { assertNull(it, api.validEndpoint(it)) }
        assertEquals("https://example.org", api.validEndpoint("  https://example.org/  "))
        assertEquals("https://example.org:8443", api.validEndpoint("https://example.org:8443/"))
    }

    @Test fun publicDynamicBoardNeverReusesACacheableOldResponse() = runBlocking {
        val cached = client.newBuilder().cache(okhttp3.Cache(java.io.File(temporary, "dynamic-cache"), 1024 * 1024)).build()
        val boardApi = ApiClient(sessions, temporary, cached).apply { baseUrl = origin(server) }
        server.enqueue(json("{\"version\":1}").setHeader("Cache-Control", "public,max-age=86400"))
        server.enqueue(json("{\"version\":2}").setHeader("Cache-Control", "public,max-age=86400"))
        assertEquals(1, boardApi.call("/api/machines?dorm=building", auth = false).getInt("version"))
        assertEquals(2, boardApi.call("/api/machines?dorm=building", auth = false).getInt("version"))
        repeat(2) { val request = server.takeRequest(1, TimeUnit.SECONDS)!!; assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-cache")); assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-store")); assertNull(request.getHeader("Authorization")) }
        cached.cache?.close()
        Unit
    }

    @Test fun refreshUnauthorizedInvalidatesOnceAndNeverSendsTheOldAccessToken() = runBlocking {
        expired()
        var invalidations = 0; api.onSessionInvalidated = { invalidations++ }
        server.enqueue(json("{\"error\":{\"code\":\"invalid_refresh_token\",\"eventId\":\"evt_refresh\",\"message\":\"重新登录\"}}").setResponseCode(401))
        try { api.call("/api/tasks"); fail("Expected expired session") } catch (e: ApiException) { assertEquals(401, e.status) }
        assertNull(sessions.read()); assertEquals(1, invalidations)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(request.getHeader("Authorization")); assertEquals("/api/mobile/auth/refresh", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun previousRefresh401CannotCancelAnInFlightNativeExchange() = runBlocking {
        expired()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val exchangeEntered = CountDownLatch(1); val exchangeRelease = CountDownLatch(1)
        val invalidations = AtomicInteger()
        api.onSessionInvalidated = { invalidations.incrementAndGet() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> {
                    entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                    json("{\"error\":{\"code\":\"invalid_refresh_token\",\"message\":\"旧会话过期\"}}").setResponseCode(401)
                }
                "/api/mobile/auth/exchange" -> {
                    assertNull(request.getHeader("Authorization"))
                    exchangeEntered.countDown(); assertTrue(exchangeRelease.await(4, TimeUnit.SECONDS))
                    json(refreshed)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        supervisorScope {
            val old = async(Dispatchers.Default) { api.call("/api/tasks") }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS))
                val endpoint = api.baseUrl; val installation = sessions.installationId
                api.beginNativeLogin(endpoint)
                assertNull(sessions.read())
                assertEquals(endpoint, api.baseUrl); assertEquals(installation, sessions.installationId)
                val fresh = async(Dispatchers.Default) { api.call("/api/mobile/auth/exchange", "POST", JSONObject(), auth = false) }
                assertTrue(exchangeEntered.await(4, TimeUnit.SECONDS))
                release.countDown()
                try { old.await(); fail("The previous refresh should report its 401") } catch (e: ApiException) { assertEquals(401, e.status) }
                exchangeRelease.countDown()
                api.saveSession(fresh.await(), endpoint)
                assertEquals("new-access", sessions.read()?.str("accessToken"))
                assertEquals(1, invalidations.get())
            } finally { release.countDown(); exchangeRelease.countDown() }
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun lateOldAccess401BeforeAndAfterSavingExchangeCannotEraseTheNewSession() = runBlocking {
        valid()
        val entered = CountDownLatch(2); val releaseBeforeSave = CountDownLatch(1); val releaseAfterSave = CountDownLatch(1)
        val invalidations = AtomicInteger()
        api.onSessionInvalidated = { invalidations.incrementAndGet() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/me?phase=before", "/api/auth/me?phase=after" -> {
                    assertEquals("Bearer valid-access", request.getHeader("Authorization"))
                    entered.countDown()
                    assertTrue((if (request.path!!.endsWith("before")) releaseBeforeSave else releaseAfterSave).await(4, TimeUnit.SECONDS))
                    json("{\"error\":{\"code\":\"unauthorized\",\"message\":\"旧凭据失效\"}}").setResponseCode(401)
                }
                "/api/mobile/auth/exchange" -> json(refreshed)
                else -> MockResponse().setResponseCode(404)
            }
        }
        supervisorScope {
            val before = async(Dispatchers.Default) { api.call("/api/auth/me?phase=before") }
            val after = async(Dispatchers.Default) { api.call("/api/auth/me?phase=after") }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS))
                api.beginNativeLogin()
                val response = api.call("/api/mobile/auth/exchange", "POST", JSONObject(), auth = false)
                // The successful code has been consumed remotely, but saveSession
                // has not happened yet. A late old 401 must remain harmless here.
                releaseBeforeSave.countDown()
                try { before.await(); fail("Expected the old access failure") } catch (e: ApiException) { assertEquals(401, e.status) }
                api.saveSession(response)
                releaseAfterSave.countDown()
                try { after.await(); fail("Expected the second old access failure") } catch (e: ApiException) { assertEquals(401, e.status) }
                assertEquals("new-access", sessions.read()?.str("accessToken"))
                assertEquals("new-refresh", sessions.read()?.str("refreshToken"))
                assertEquals(1, invalidations.get())
            } finally { releaseBeforeSave.countDown(); releaseAfterSave.countDown() }
        }
    }

    @Test fun newLoginProfileDoesNotWaitBehindAnObsoleteRefresh() = runBlocking {
        expired()
        val refreshEntered = CountDownLatch(1); val releaseRefresh = CountDownLatch(1)
        val profileEntered = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> {
                    refreshEntered.countDown(); assertTrue(releaseRefresh.await(4, TimeUnit.SECONDS))
                    json("{\"error\":{\"code\":\"invalid_refresh_token\"}}").setResponseCode(401)
                }
                "/api/mobile/auth/exchange" -> json(refreshed)
                "/api/auth/me" -> {
                    assertEquals("Bearer new-access", request.getHeader("Authorization"))
                    profileEntered.countDown()
                    json("{\"user\":{\"id\":\"new-account\"}}")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        supervisorScope {
            val old = async(Dispatchers.Default) { api.call("/api/tasks") }
            try {
                assertTrue(refreshEntered.await(4, TimeUnit.SECONDS))
                api.beginNativeLogin()
                api.saveSession(api.call("/api/mobile/auth/exchange", "POST", JSONObject(), auth = false))
                val profile = async(Dispatchers.Default) { api.call("/api/auth/me") }
                assertTrue("The new account must load while the previous refresh is still blocked", profileEntered.await(2, TimeUnit.SECONDS))
                assertEquals(1L, releaseRefresh.count)
                assertEquals("new-account", profile.await().optJSONObject("user")?.str("id"))
                releaseRefresh.countDown()
                try { old.await(); fail("The previous refresh must report its 401") } catch (e: ApiException) { assertEquals(401, e.status) }
                assertEquals("new-access", sessions.read()?.str("accessToken"))
            } finally { releaseRefresh.countDown() }
        }
    }

    @Test fun queuedProtectedCallWithoutCredentialsCannotInvalidateOrReachAnActiveExchange() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val invalidations = AtomicInteger()
        api.onSessionInvalidated = { invalidations.incrementAndGet() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/mobile/auth/exchange", request.path)
                entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                return json(refreshed)
            }
        }
        api.beginNativeLogin()
        supervisorScope {
            val fresh = async(Dispatchers.Default) { api.call("/api/mobile/auth/exchange", "POST", JSONObject(), auth = false) }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS))
                try { api.call("/api/tasks"); fail("A protected read requires local credentials") }
                catch (e: ApiException) { assertEquals(401, e.status); assertTrue(e.eventId.startsWith("client_")) }
                assertEquals("Only the deliberate login boundary invalidates the session", 1, invalidations.get())
                release.countDown()
                api.saveSession(fresh.await())
                assertEquals("new-access", sessions.read()?.str("accessToken"))
            } finally { release.countDown() }
        }
        assertEquals("The stale protected child must not issue an anonymous HTTP request", 1, server.requestCount)
    }

    @Test fun transientProfileFailureKeepsEncryptedIdentityAndValidCredentials() = runBlocking {
        valid()
        api.cacheProfile(JSONObject().put("id", "saved-user").put("nick", "已保存昵称"))
        val before = sessions.read().toString()
        val invalidations = AtomicInteger()
        api.onSessionInvalidated = { invalidations.incrementAndGet() }
        server.enqueue(json("""{"error":{"code":"unavailable","message":"稍后重试"}}""").setResponseCode(503))
        try { api.call("/api/auth/me"); fail("Expected a transient error") } catch (e: ApiException) { assertEquals(503, e.status) }
        assertEquals(before, sessions.read().toString())
        assertEquals("已保存昵称", api.cachedProfile()?.nick)
        assertEquals(0, invalidations.get())
        assertEquals("Bearer valid-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun refreshingCredentialsPreservesFullProfileOnlyForTheSameAccountAndLogoutClearsIt() = runBlocking {
        expired()
        sessions.save(sessions.read()!!.put("user", JSONObject().put("id", "saved-user")))
        api.cacheProfile(JSONObject().put("id", "saved-user").put("nick", "缓存资料").put("dingNick", "关联昵称"))
        server.enqueue(json(JSONObject(refreshed).put("user", JSONObject().put("id", "saved-user")).toString()))
        server.enqueue(json("""{"user":{"id":"saved-user"}}"""))
        api.call("/api/auth/me")
        assertEquals("关联昵称", api.cachedProfile()?.dingNick)
        assertEquals("new-access", sessions.read()?.str("accessToken"))
        api.saveSession(JSONObject(refreshed).put("user", JSONObject().put("id", "next-user").put("nick", "新账号")))
        assertEquals("next-user", api.cachedProfile()?.id)
        assertEquals("", api.cachedProfile()?.dingNick)
        api.cacheProfile(JSONObject().put("id", "saved-user").put("nick", "迟到旧资料"))
        assertEquals("next-user", api.cachedProfile()?.id)
        api.beginNativeLogin()
        assertNull(api.cachedProfile())
        assertNull(sessions.read())
    }

    @Test fun independentClientsSerializeRotationBeforeALosing401CanEraseTheWinningSession() = runBlocking {
        expired()
        val background = ApiClient(sessions, temporary, client).apply { baseUrl = api.baseUrl }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val refreshes = AtomicInteger(); val reads = AtomicInteger(); val invalidations = AtomicInteger()
        api.onSessionInvalidated = { invalidations.incrementAndGet() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> if (refreshes.incrementAndGet() == 1) {
                    entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                    json(refreshed)
                } else json("""{"error":{"code":"invalid_refresh_token"}}""").setResponseCode(401)
                "/api/tasks" -> {
                    assertEquals("Bearer new-access", request.getHeader("Authorization")); reads.incrementAndGet()
                    json("""{"tasks":[]}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        supervisorScope {
            val first = async(Dispatchers.Default) { api.call("/api/tasks") }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS))
                val started = CountDownLatch(1)
                val waiting = async(Dispatchers.Default) { started.countDown(); background.call("/api/tasks") }
                assertTrue(started.await(4, TimeUnit.SECONDS)); delay(100)
                assertEquals("A second client must wait before spending the same refresh secret", 1, refreshes.get())
                assertFalse(waiting.isCompleted)
                release.countDown(); first.await(); waiting.await()
            } finally { release.countDown() }
        }
        assertEquals("new-refresh", sessions.read()?.str("refreshToken")); assertEquals(0, invalidations.get())
        assertEquals(2, reads.get()); assertEquals(1, refreshes.get())
        api.call("/api/tasks")
        assertEquals(3, reads.get()); assertEquals(1, refreshes.get())
    }

    @Test fun lateRefreshSuccessOr401CannotReplaceOrClearAnotherClientsExplicitNewCredentials() = runBlocking {
        val background = ApiClient(sessions, temporary, client).apply { baseUrl = api.baseUrl }
        for (status in listOf(200, 401)) {
        expired()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val before = server.requestCount
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> {
                    entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                    json(if (status == 200) """{"accessToken":"obsolete-access","refreshToken":"obsolete-refresh","expiresIn":900}"""
                        else """{"error":{"code":"invalid_refresh_token"}}""").setResponseCode(status)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        supervisorScope {
            val obsolete = async(Dispatchers.Default) { api.call("/api/tasks") }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS)); background.saveSession(JSONObject(refreshed)); release.countDown()
                try { obsolete.await(); fail("A late successful refresh cannot replace the winner") } catch (_: CancellationException) { }
            } finally { release.countDown() }
        }
        assertEquals("new-access", sessions.read()?.str("accessToken")); assertEquals("new-refresh", sessions.read()?.str("refreshToken"))
        assertEquals("The obsolete client must not send or replay a protected request", before + 1, server.requestCount)
        }
    }

    @Test fun oldProtected401CannotClearCredentialsRotatedByAnotherClient() = runBlocking {
        valid()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val invalidations = AtomicInteger()
        api.onSessionInvalidated = { invalidations.incrementAndGet() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer valid-access", request.getHeader("Authorization"))
                entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                return json("""{"error":{"code":"unauthorized"}}""").setResponseCode(401)
            }
        }
        supervisorScope {
            val obsolete = async(Dispatchers.Default) { api.call("/api/auth/me") }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS))
                ApiClient(sessions, temporary, client).apply { baseUrl = api.baseUrl }.saveSession(JSONObject(refreshed))
                release.countDown()
                try { obsolete.await(); fail("An old credential failure must be cancelled") } catch (_: CancellationException) { }
            } finally { release.countDown() }
        }
        assertEquals("new-access", sessions.read()?.str("accessToken")); assertEquals(0, invalidations.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun successfulOldAccountMutationCannotPublishIntoAnotherClientsNewLoginOrReplay() = runBlocking {
        valid()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("POST", request.method); assertEquals("Bearer valid-access", request.getHeader("Authorization"))
                entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                return json("""{"ok":true,"privateResult":"old-account"}""")
            }
        }
        supervisorScope {
            val obsolete = async(Dispatchers.Default) { api.call("/api/tasks", "POST", JSONObject().put("minutes", 40)) }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS))
                ApiClient(sessions, temporary, client).apply { baseUrl = api.baseUrl }.saveSession(JSONObject(refreshed).put("user", JSONObject().put("id", "new-account")))
                release.countDown()
                try { obsolete.await(); fail("Private results belong to their captured authorization") } catch (_: CancellationException) { }
            } finally { release.countDown() }
        }
        assertEquals("new-account", sessions.read()?.optJSONObject("user")?.str("id"))
        assertEquals("A completed mutation must never be replayed under the new account", 1, server.requestCount)
    }

    @Test fun profileCacheChangedDuringRefreshPreservesBothTheLatestProfileAndUniqueRotatedCredentials() = runBlocking {
        expired()
        sessions.save(sessions.read()!!.put("user", JSONObject().put("id", "same-account")))
        api.cacheProfile(JSONObject().put("id", "same-account").put("nick", "old-profile"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> {
                    entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
                    json(JSONObject(refreshed).put("user", JSONObject().put("id", "same-account")).toString())
                }
                "/api/tasks" -> { assertEquals("Bearer new-access", request.getHeader("Authorization")); json("""{"tasks":[]}""") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        supervisorScope {
            val refreshing = async(Dispatchers.Default) { api.call("/api/tasks") }
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS))
                ApiClient(sessions, temporary, client).apply { baseUrl = api.baseUrl }
                    .cacheProfile(JSONObject().put("id", "same-account").put("nick", "latest-profile").put("dingNick", "latest-ding-profile"))
                release.countDown(); refreshing.await()
            } finally { release.countDown() }
        }
        assertEquals("new-access", sessions.read()?.str("accessToken")); assertEquals("new-refresh", sessions.read()?.str("refreshToken"))
        assertEquals("latest-profile", api.cachedProfile()?.nick); assertEquals("latest-ding-profile", api.cachedProfile()?.dingNick)
        assertEquals(2, server.requestCount)
    }

    @Test fun protectedAndCsv401WaitForAConcurrentRotationWhoseWinningResponseHasNotBeenStored() = runBlocking {
        for (csv in listOf(false, true)) {
            val path = if (csv) "/api/admin/users.csv" else "/api/auth/me?old"
            val entered = CountDownLatch(1); val releaseOld = CountDownLatch(1)
            val rotatedRemotely = CountDownLatch(1); val releaseWinner = CountDownLatch(1)
            val received401 = CountDownLatch(1); val invalidated = CountDownLatch(1)
            val before = server.requestCount
            val observed = client.newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
                    if (call.request().url.encodedPath == path.substringBefore('?')) received401.countDown()
                }
            }).build()
            val foreground = ApiClient(sessions, temporary, observed).apply { baseUrl = origin(server); onSessionInvalidated = { invalidated.countDown() } }
            val background = ApiClient(sessions, temporary, client).apply { baseUrl = origin(server) }
            // Cross the real refresh boundary without modifying the captured record.
            // Changing expiresAt in storage would itself mask the old CAS-clear bug.
            val expires = System.currentTimeMillis() + 32_000
            sessions.save(JSONObject().put("origin", foreground.baseUrl).put("accessToken", "valid-access")
                .put("refreshToken", "valid-refresh").put("expiresAt", expires))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    path -> {
                        assertEquals("Bearer valid-access", request.getHeader("Authorization"))
                        entered.countDown(); assertTrue(releaseOld.await(4, TimeUnit.SECONDS))
                        json("""{"error":{"code":"unauthorized"}}""").setResponseCode(401)
                    }
                    "/api/mobile/auth/refresh" -> {
                        rotatedRemotely.countDown(); assertTrue(releaseWinner.await(4, TimeUnit.SECONDS))
                        json(refreshed)
                    }
                    "/api/tasks" -> { assertEquals("Bearer new-access", request.getHeader("Authorization")); json("""{"tasks":[]}""") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            supervisorScope {
                val old = async(Dispatchers.Default) { if (csv) foreground.adminCsv(path) else foreground.call(path) }
                try {
                    assertTrue(entered.await(4, TimeUnit.SECONDS))
                    delay((expires - System.currentTimeMillis() - 30_000 + 25).coerceAtLeast(0))
                    val winner = async(Dispatchers.Default) { background.call("/api/tasks") }
                    assertTrue(rotatedRemotely.await(4, TimeUnit.SECONDS))
                    releaseOld.countDown(); assertTrue(received401.await(4, TimeUnit.SECONDS))
                    assertFalse("401 must wait for the pending successful rotation", invalidated.await(150, TimeUnit.MILLISECONDS))
                    assertFalse(old.isCompleted); assertEquals("valid-access", sessions.read()?.str("accessToken"))
                    releaseWinner.countDown(); winner.await()
                    try { old.await(); fail("The obsolete response must cancel after the winner is saved") } catch (_: CancellationException) { }
                } finally { releaseOld.countDown(); releaseWinner.countDown() }
            }
            assertEquals("new-access", sessions.read()?.str("accessToken")); assertEquals(1L, invalidated.count)
            assertEquals("Neither the old read nor export may be replayed", before + 3, server.requestCount)
        }
    }
}
