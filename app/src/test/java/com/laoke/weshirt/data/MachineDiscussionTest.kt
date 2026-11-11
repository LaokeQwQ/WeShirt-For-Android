package com.laoke.weshirt.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MachineDiscussionTest {
    private val session = object : SessionStore {
        override val installationId = "discussion-test"
        override fun read() = JSONObject().put("origin", "https://example.invalid").put("accessToken", "test-access")
            .put("expiresAt", System.currentTimeMillis() + 600_000)
        override fun save(value: JSONObject) = Unit
        override fun clear() = Unit
    }

    @Test fun machineCommentsUseAuthenticatedDormFilterAndPreserveMuteAndReplyMetadata() = runBlocking {
        val temporary = Files.createTempDirectory("machine-comments").toFile()
        var path = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); path = request.url.encodedPath + "?" + request.url.encodedQuery
            assertEquals("Bearer test-access", request.header("Authorization"))
            assertEquals("楼栋 A", request.url.queryParameter("channel"))
            assertEquals("machine/一号", request.url.queryParameter("machineId"))
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"messages":[{"id":"message-1","nick":"用户","text":"门锁正常","identity":{"label":"志愿管理"},"reply":{"nick":"同学","text":"门锁如何？"},"pinned":true}],"mute":{"active":true},"pagination":{"hasMore":true}}""".toResponseBody()).build()
        }.build()
        try {
            val api = ApiClient(session, temporary, client).apply { baseUrl = "https://example.invalid" }
            val result = MachineDiscussion(api).load("楼栋 A", "machine/一号", 2)
            assertTrue(path.startsWith("/api/messages?channel="))
            assertTrue(path.endsWith("&limit=20&page=2"))
            assertTrue(result.muted); assertTrue(result.hasMore)
            assertEquals("志愿管理", result.messages.single().role)
            assertEquals("同学: 门锁如何？", result.messages.single().reply)
            assertTrue(result.messages.single().pinned)
        } finally { temporary.deleteRecursively(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun postingKeepsMachineAndChannelAndReturnsTraceableServerPermissionErrors() = runBlocking {
        val temporary = Files.createTempDirectory("machine-comment-post").toFile()
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); posts++
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("building-a", body.getString("channel")); assertEquals("machine-1", body.getString("machineId"))
            assertEquals("新的使用情况", body.getString("text")); assertEquals("reply-1", body.getString("replyTo"))
            assertNotNull(request.header("Idempotency-Key"))
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("""{"error":{"code":"muted","message":"暂时不能留言","eventId":"event-muted"}}""".toResponseBody()).build()
        }.build()
        try {
            val api = ApiClient(session, temporary, client).apply { baseUrl = "https://example.invalid" }
            val problem = runCatching { MachineDiscussion(api).post("building-a", "machine-1", "  新的使用情况  ", "reply-1") }.exceptionOrNull() as ApiException
            assertEquals(403, problem.status); assertEquals("muted", problem.code); assertEquals("event-muted", problem.eventId)
            assertEquals(1, posts)
        } finally { temporary.deleteRecursively(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
