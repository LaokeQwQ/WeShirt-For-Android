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
}
