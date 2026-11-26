package com.laoke.weshirt.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AssistantStreamingTest {
    private class MemorySession : SessionStore {
        override val installationId = "streaming-fixture"
        private var value: String? = null
        @Synchronized override fun read() = value?.let(::JSONObject)
        @Synchronized override fun save(value: JSONObject) { this.value = value.toString() }
        @Synchronized override fun clear() { value = null }
    }
    private val directory = Files.createTempDirectory("assistant-stream-test").toFile()
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: ApiClient

    @Before fun prepare() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start(InetAddress.getByName("127.0.0.1"), 0) }
        http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        api = ApiClient(MemorySession(), directory, http).apply { baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/') }
        api.saveSession(JSONObject().put("accessToken", "stream-access").put("refreshToken", "stream-refresh").put("expiresIn", 900))
    }
    @After fun cleanup() {
        server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); directory.deleteRecursively()
    }
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setHeader("X-Event-ID", "evt_stream").setBody(body)
    private val question get() = JSONObject().put("input", "哪台空闲？")

    @Test fun unicodeTokensBecomeVisibleBeforeDoneAndTheAuthenticatedPostIsSentOnce() = runBlocking {
        val wire = ": ready\r\n\r\nevent: delta\r\ndata: {\"text\":\"衣服\"}\r\n\r\n" +
            "event: delta\r\ndata: {\"text\":\"洗好了\"}\r\n\r\n" +
            "event: done\r\ndata: {\"reply\":\"衣服洗好了\",\"messages\":[{\"role\":\"assistant\",\"content\":\"衣服洗好了\"}],\"usage\":{\"dailyRemaining\":2}}\r\n\r\n"
        server.enqueue(sse(wire).throttleBody(1, 2, TimeUnit.MILLISECONDS))
        val first = CompletableDeferred<Unit>()
        val parts = CopyOnWriteArrayList<String>()
        val pending = async(Dispatchers.Default) { api.streamAssistant(question) { parts += it; first.complete(Unit) } }
        withTimeout(4000) { first.await() }
        assertFalse("The first Unicode delta must appear before the final response", pending.isCompleted)
        val done = withTimeout(6000) { pending.await() }
        assertEquals("衣服洗好了", parts.joinToString("")); assertEquals("衣服洗好了", done.getString("reply"))
        assertEquals(2, done.getJSONObject("usage").getInt("dailyRemaining"))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/api/assistant/chat/stream", request.path)
        assertEquals("Bearer stream-access", request.getHeader("Authorization")); assertEquals("text/event-stream", request.getHeader("Accept"))
        assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-store"))
        assertEquals(1, server.requestCount)
    }

    @Test fun partialAnswerThenServerErrorRetainsTraceAndDoesNotRetryThePaidPost() = runBlocking {
        server.enqueue(sse("event: delta\ndata: {\"text\":\"部分回答\"}\n\nevent: error\ndata: {\"error\":{\"status\":502,\"code\":\"upstream_error\",\"eventId\":\"evt_model_failed\",\"message\":\"服务暂不可用\"}}\n\n"))
        val parts = mutableListOf<String>()
        try { api.streamAssistant(question) { parts += it }; fail("An error cannot confirm history") }
        catch (problem: ApiException) { assertEquals(502, problem.status); assertEquals("evt_model_failed", problem.eventId) }
        assertEquals(listOf("部分回答"), parts); assertEquals(1, server.requestCount)
    }

    @Test fun preStreamHttpErrorAndUnexpectedEofAreReportedWithoutReplay() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
            .setBody("{\"error\":{\"code\":\"rate_limit\",\"eventId\":\"evt_quota\",\"message\":\"额度用完\"}}"))
        try { api.streamAssistant(question) {}; fail("Expected quota rejection") }
        catch (problem: ApiException) { assertEquals(429, problem.status); assertEquals("evt_quota", problem.eventId) }
        server.enqueue(sse("event: delta\ndata: {\"text\":\"尚未完成\"}\n\n"))
        try { api.streamAssistant(question) {}; fail("A missing done event cannot be accepted") }
        catch (problem: ApiException) { assertEquals("assistant_stream_interrupted", problem.code) }
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellingABlockedProviderReadClosesTheCallImmediately() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { api.streamAssistant(question) {} }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        withTimeout(2000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled); assertEquals(1, server.requestCount)
    }
}
