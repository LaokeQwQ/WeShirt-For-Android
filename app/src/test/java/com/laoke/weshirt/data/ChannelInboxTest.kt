package com.laoke.weshirt.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChannelInboxTest {
    @Test fun aClippedPixelIsNotReadButAReadablePartOfALongMessageQualifies() {
        assertFalse(isMessageVisible(99, 100, 0, 100, 120))
        assertFalse(isMessageVisible(36, 100, 0, 100, 120))
        assertTrue(isMessageVisible(35, 100, 0, 100, 120))
        assertTrue(isMessageVisible(0, 800, 0, 120, 120))
        assertFalse(isMessageVisible(0, 800, 0, 119, 120))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun visibleHttpFallbackWaitsThirtySecondsAndStopsForRelayScopeAndCancellation() = runTest {
        var current = true
        var connected = false
        var refreshes = 0
        val job = launch { channelHttpFallback({ current }, { connected }) { refreshes++ } }
        runCurrent(); advanceTimeBy(29_999); runCurrent(); assertEquals(0, refreshes)
        advanceTimeBy(1); runCurrent(); assertEquals(1, refreshes)
        connected = true; advanceTimeBy(30_000); runCurrent(); assertEquals(1, refreshes)
        connected = false; current = false; advanceTimeBy(30_000); runCurrent(); assertEquals(1, refreshes)
        current = true; job.cancel(); advanceTimeBy(30_000); runCurrent(); assertEquals(1, refreshes)
    }
    @Test fun directoryCachesOnlyWithinTheSameAccountAndNeverAcknowledgesOrFetchesMessages() = runBlocking {
        val directory = Files.createTempDirectory("channel-inbox").toFile()
        var requestCount = 0
        var now = 1L
        val session = object : SessionStore {
            override val installationId = "channel-inbox"
            override fun read() = JSONObject().put("origin", "https://example.invalid").put("accessToken", "fixture")
                .put("expiresAt", System.currentTimeMillis() + 600_000)
            override fun save(value: JSONObject) = Unit
            override fun clear() = Unit
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestCount++
            val request = chain.request()
            assertEquals("/api/messages/channels", request.url.encodedPath)
            assertEquals("GET", request.method)
            assertEquals("Bearer fixture", request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"channels":[{"code":"public","name":"公共频道","unreadCount":3,"summary":{"messageId":"latest","nick":"李**同学","text":"你好","kind":"text","at":"2030-01-01T00:00:00Z"}}]}""".toResponseBody()).build()
        }.build()
        try {
            val inbox = ChannelInbox(ApiClient(session, directory, client).apply { baseUrl = "https://example.invalid" }) { now }
            val first = ChannelAccountScope("https://example.invalid", "user-a", 1)
            assertEquals("李**同学:你好", inbox.load(first) { true }.single().summary!!.preview)
            assertEquals(3, inbox.load(first) { true }.single().unreadCount)
            assertEquals(1, requestCount)
            inbox.load(first.copy(userId = "user-b")) { true }
            inbox.load(first.copy(generation = 2)) { true }
            inbox.load(first.copy(origin = "https://other.invalid")) { true }
            assertEquals("Account, login generation and origin must each invalidate cached authorization", 4, requestCount)
            now += 10_001
            inbox.load(first.copy(origin = "https://other.invalid")) { true }
            assertEquals(5, requestCount)
        } finally { directory.deleteRecursively(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun readAcknowledgesOnlyVisibleIncomingIdsAndStaleScopeDoesNotPublishOrSend() = runBlocking {
        val directory = Files.createTempDirectory("channel-read").toFile()
        var posts = 0
        val session = object : SessionStore {
            override val installationId = "read"
            override fun read() = JSONObject().put("origin", "https://example.invalid").put("accessToken", "fixture").put("expiresAt", System.currentTimeMillis() + 600_000)
            override fun save(value: JSONObject) = Unit
            override fun clear() = Unit
        }
        var current = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); posts++
            assertEquals("/api/messages/read", request.url.encodedPath)
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("building-a", body.getString("channel"))
            assertEquals(listOf("visible"), (0 until body.getJSONArray("messageIds").length()).map { body.getJSONArray("messageIds").getString(it) })
            assertFalse("A cursor must never acknowledge unseen history", body.has("throughMessageId"))
            current = false // Account changed while the request was in flight.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":true,"unreadCount":8}""".toResponseBody()).build()
        }.build()
        try {
            val messages = listOf("visible", "unseen", "mine").map { id -> Message.parse(JSONObject().put("id", id).put("mine", id == "mine")) }
            val visible = visibleIncomingMessageIds(messages, listOf("header", "visible", "mine", "visible", 3))
            assertEquals(listOf("visible"), visible)
            val inbox = ChannelInbox(ApiClient(session, directory, client).apply { baseUrl = "https://example.invalid" })
            val scope = ChannelAccountScope("https://example.invalid", "user-a", 1)
            val failure = runCatching { inbox.acknowledge(scope, "building-a", visible) { current } }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            runCatching { inbox.acknowledge(scope, "building-a", visible) { current } }
            assertEquals("Stale scope must fail before another HTTP request", 1, posts)
        } finally { directory.deleteRecursively(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun directoryPreviewsNeverExposeMediaDetailsOrEmbeddedNewlines() {
        assertEquals("李**同学:[图片]", ChannelSummary("id", "李**同学", "private-media-key", "image", "").preview)
        assertEquals("李**同学:[视频]", ChannelSummary("id", "李**同学", "https://private.invalid", "video", "").preview)
        assertEquals("李**同学:你好 同学", ChannelSummary("id", "李**同学", "你好\n同学", "text", "").preview)
        assertNull(Channel.parse(JSONObject().put("code", "public")).summary)
    }
}
