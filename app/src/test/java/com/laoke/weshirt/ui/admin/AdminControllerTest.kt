package com.laoke.weshirt.ui.admin

import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.SessionStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.atomic.AtomicBoolean

class AdminControllerTest {
    private class MemorySession : SessionStore {
        override val installationId = "test-admin"
        private var value: String? = null
        override fun read() = value?.let(::JSONObject)
        override fun save(value: JSONObject) { this.value = value.toString() }
        override fun clear() { value = null }
    }
    private val temporary = Files.createTempDirectory("weshirt-admin-controller").toFile()
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: ApiClient
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    @Before fun prepare() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start(InetAddress.getByName("127.0.0.1"), 0) }
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        api = ApiClient(MemorySession(), temporary, http).apply { baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/') }
        api.saveSession(JSONObject().put("accessToken", "fixture-admin-access").put("refreshToken", "fixture-admin-refresh").put("expiresIn", 900))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                if (!release.await(4, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"sensitive\":\"old-admin-document\"}")
            }
        }
    }
    @After fun finish() {
        release.countDown(); server.shutdown()
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        temporary.deleteRecursively()
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(4_000) { while (!condition()) delay(10) }

    @Test fun aClosedPanelCannotPublishALateAdminRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AdminController(api, scope) { true }
        try {
            controller.load("/api/admin/site")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.close()
            release.countDown()
            delay(150)
            assertEquals(0, controller.document.length())
            assertNull(controller.error)
            assertEquals(1, server.requestCount)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun repeatedMutationWhilePendingIsSentOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AdminController(api, scope) { true }
        try {
            controller.act("/api/admin/site", "PATCH", JSONObject().put("name", "first"), refresh = null)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.act("/api/admin/site", "PATCH", JSONObject().put("name", "second"), refresh = null)
            assertTrue(controller.saving)
            release.countDown(); waitFor { !controller.saving }
            assertEquals(1, server.requestCount)
            assertEquals("first", JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getString("name"))
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun closingAnInflightMutationPreventsItsFollowupRefreshAndSuccessCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AdminController(api, scope) { true }
        val success = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        try {
            controller.act("/api/admin/site", "PATCH", JSONObject().put("name", "updated"), refresh = "/api/admin/site", finished = { finished.set(true) }, success = { success.set(true) })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.close(); release.countDown()
            waitFor { finished.get() }
            assertFalse(success.get())
            assertEquals(0, controller.document.length())
            assertEquals(1, server.requestCount)
        } finally { controller.close(); scope.cancel() }
    }
}
