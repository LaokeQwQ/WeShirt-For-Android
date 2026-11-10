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
}
