package com.laoke.weshirt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real TLS and WebSocket frames; no mock Android clock or credential implementation. */
class ChannelRealtimeTest {
    private class MemorySession : SessionStore {
        override val installationId = "realtime_test_installation"
        private var value: String? = null
        @Synchronized override fun read() = value?.let(::JSONObject)
        @Synchronized override fun save(value: JSONObject) { this.value = value.toString() }
        @Synchronized override fun clear() { value = null }
    }

    private lateinit var backend: MockWebServer
    private lateinit var relay: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var session: MemorySession
    private lateinit var api: ApiClient
    private val clientSockets = CopyOnWriteArrayList<WebSocket>()
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    private val temporary = Files.createTempDirectory("weshirt-realtime-test").toFile()

    @Before fun prepare() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverKeys = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val loopback = InetAddress.getByName("127.0.0.1")
        backend = MockWebServer().apply { useHttps(serverKeys.sslSocketFactory(), false); start(loopback, 0) }
        relay = MockWebServer().apply { useHttps(serverKeys.sslSocketFactory(), false); start(loopback, 0) }
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
        session = MemorySession()
        api = ApiClient(session, temporary, client).apply { baseUrl = origin(backend) }
        api.saveSession(JSONObject().put("accessToken", ACCESS).put("refreshToken", REFRESH).put("expiresIn", 900))
    }

    @After fun finish() {
        clientSockets.forEach(WebSocket::cancel)
        // MockWebServer's upgraded RealWebSocket has no client Call, so cancel()
        // is unsupported there. Close its writer; shutdown releases the peer transport.
        serverSockets.forEach { it.close(1000, null) }
        client.dispatcher.cancelAll()
        backend.shutdown(); relay.shutdown()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        temporary.deleteRecursively()
    }

    @Test fun crossOriginHandshakeNeverCarriesCredentialsAndCancellationClosesTheSocket() = runBlocking {
        val frames = LinkedBlockingQueue<String>()
        val peer = AtomicReference<WebSocket>()
        val disconnected = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val changes = CountDownLatch(2)
        val connected = CopyOnWriteArrayList<Boolean>()
        relay.enqueue(upgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets += webSocket; peer.set(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.offer(text)
                if (JSONObject(text).str("type") == "authenticate") webSocket.send("{\"type\":\"ready\",\"channel\":\"public\"}")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected.countDown() }
        }))
        backend.enqueue(json(configuration()))
        val task = launch(Dispatchers.Default) {
            ChannelRealtime(api).run("public", { changes.countDown() }, { value -> connected += value; if (value) ready.countDown() })
        }
        try {
            val request = relay.takeRequest(4, TimeUnit.SECONDS)
            assertNotNull("A real TLS WebSocket handshake must reach the relay", request)
            assertEquals("/channel", request!!.path)
            assertEquals(origin(backend), request.getHeader("Origin"))
            assertEquals(PROTOCOL, request.getHeader("Sec-WebSocket-Protocol"))
            listOf("Authorization", "Cookie", "X-Request-ID", "Idempotency-Key").forEach { assertNull(it, request.getHeader(it)) }
            assertEquals(0L, request.bodySize)
            assertFalse(request.headers.toString().contains(ACCESS))
            assertFalse(request.headers.toString().contains(REFRESH))
            assertFalse(request.headers.toString().contains(TICKET))
            val apiRequest = backend.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/messages/realtime?channel=public", apiRequest.path)
            assertEquals("Bearer $ACCESS", apiRequest.getHeader("Authorization"))
            val frame = frames.poll(4, TimeUnit.SECONDS)
            assertNotNull("The short ticket must be sent as a WebSocket frame", frame)
            val authentication = JSONObject(frame!!)
            assertEquals(setOf("type", "ticket"), authentication.keys().asSequence().toSet())
            assertEquals("authenticate", authentication.str("type"))
            assertEquals(TICKET, authentication.str("ticket"))
            assertFalse(frame.contains(ACCESS)); assertFalse(frame.contains(REFRESH))
            assertTrue(ready.await(4, TimeUnit.SECONDS))
            assertTrue(peer.get().send("{\"type\":\"channel.changed\",\"channel\":\"public\"}"))
            assertTrue("Ready and invalidation should each update visible channel data", changes.await(4, TimeUnit.SECONDS))
            task.cancelAndJoin()
            assertTrue("Cancellation must close the actual upgraded socket", disconnected.await(4, TimeUnit.SECONDS))
            assertFalse(task.isActive)
            assertTrue(task.children.none())
            assertEquals(false, connected.last())
            assertEquals(1, backend.requestCount)
            assertNull("No heartbeat or extra credential frame may remain after cancellation", frames.poll(100, TimeUnit.MILLISECONDS))
        } finally { task.cancelAndJoin() }
    }

    @Test fun unsafeRelayAddressesAndExpiredOrOversizedTicketsAreRejectedBeforeNetworkAccess() {
        val valid = configuration()
        val urls = listOf(
            "ws://127.0.0.1/channel", "https://127.0.0.1/channel", "wss://user:password@127.0.0.1/channel",
            "$socketUrl?ticket=$TICKET", "$socketUrl#fragment", "wss://", " wss://127.0.0.1/channel",
        )
        val invalid = urls.map { JSONObject(valid.toString()).put("url", it) } + listOf(
            JSONObject(valid.toString()).put("protocol", "other.v1"),
            JSONObject(valid.toString()).put("ticket", ""),
            JSONObject(valid.toString()).put("ticket", "x".repeat(4097)),
            JSONObject(valid.toString()).put("expiresAt", System.currentTimeMillis() - 1),
            JSONObject(valid.toString()).put("expiresAt", System.currentTimeMillis() + 300_000),
        )
        invalid.forEachIndexed { index, config ->
            try { clientSockets += api.channelSocket(config, object : WebSocketListener() {}); fail("Invalid relay configuration $index should be rejected") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, backend.requestCount)
        assertEquals(0, relay.requestCount)
    }

    @Test fun replacingTheSessionSuppressesOldSocketMessagesAndCancelsTheOldConnection() {
        assertInvalidationClosesOldSocket {
            api.saveSession(JSONObject().put("accessToken", "replacement_access").put("refreshToken", "replacement_refresh"))
        }
        assertEquals("replacement_access", session.read()?.str("accessToken"))
    }

    @Test fun changingTheEndpointSuppressesOldSocketMessagesAndClearsCredentials() {
        assertInvalidationClosesOldSocket { api.baseUrl = origin(relay) }
        assertNull(session.read())
        assertEquals(0, backend.requestCount)
    }

    @Test fun relayWithoutTheNegotiatedProtocolNeverReceivesAnAuthenticationTicket() = runBlocking {
        val frames = LinkedBlockingQueue<String>()
        val disconnected = CountDownLatch(1)
        val changed = AtomicInteger()
        val becameReady = AtomicInteger()
        relay.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets += webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.offer(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected.countDown() }
        }))
        backend.enqueue(json(configuration()))
        val task = launch(Dispatchers.Default) {
            ChannelRealtime(api).run("public", { changed.incrementAndGet() }, { if (it) becameReady.incrementAndGet() })
        }
        try {
            assertNotNull(relay.takeRequest(4, TimeUnit.SECONDS))
            assertTrue("Protocol mismatch must cancel the upgraded socket", disconnected.await(4, TimeUnit.SECONDS))
            assertNull(frames.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(0, changed.get()); assertEquals(0, becameReady.get())
        } finally { task.cancelAndJoin() }
    }

    private fun assertInvalidationClosesOldSocket(invalidate: () -> Unit) {
        val peer = AtomicReference<WebSocket>()
        val peerOpened = CountDownLatch(1)
        val clientOpened = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val messages = AtomicInteger()
        val failures = AtomicInteger()
        relay.enqueue(upgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets += webSocket; peer.set(webSocket); peerOpened.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected.countDown() }
        }))
        clientSockets += api.channelSocket(configuration(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { clientOpened.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) { messages.incrementAndGet() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failures.incrementAndGet() }
        })
        assertTrue(peerOpened.await(4, TimeUnit.SECONDS))
        assertTrue(clientOpened.await(4, TimeUnit.SECONDS))
        invalidate()
        assertTrue(peer.get().send("{\"type\":\"channel.changed\",\"channel\":\"public\"}"))
        assertTrue("The stale callback must cancel its socket", disconnected.await(4, TimeUnit.SECONDS))
        assertEquals("Old account messages must not reach the visible-page listener", 0, messages.get())
        assertEquals("Old connection failures must not affect the new account", 0, failures.get())
    }

    private val socketUrl get() = relay.url("/channel").newBuilder().host("127.0.0.1").build().toString().replaceFirst("https:", "wss:")
    private fun configuration() = JSONObject().put("enabled", true).put("url", socketUrl).put("protocol", PROTOCOL).put("ticket", TICKET).put("expiresAt", System.currentTimeMillis() + 90_000)
    private fun origin(server: MockWebServer) = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun upgrade(listener: WebSocketListener) = MockResponse().setHeader("Sec-WebSocket-Protocol", PROTOCOL).withWebSocketUpgrade(listener)

    companion object {
        private const val PROTOCOL = "weshirt.channel.v1"
        private const val ACCESS = "test_access_credential"
        private const val REFRESH = "test_refresh_credential"
        private const val TICKET = "test_channel_short_ticket"
    }
}
