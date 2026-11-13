package com.laoke.weshirt.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.str
import com.laoke.weshirt.platform.push.PushRuntime
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Exercises the real ViewModel, native viewport and authenticated TLS requests together. */
@RunWith(AndroidJUnit4::class)
class ChannelInboxIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val prefs get() = app.getSharedPreferences("preferences", 0)
    private val models = ViewModelStore()
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val tasksStarted = java.util.concurrent.CountDownLatch(1)
    private val releaseTasks = java.util.concurrent.CountDownLatch(1)
    private val releaseMemory = java.util.concurrent.CountDownLatch(1)
    @Volatile private var memoryRecovered = false
    private val readIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var previous = emptyMap<String, Any?>()
    @Volatile private var replaceVisibleMessage = false
    @Volatile private var sentText = ""
    private lateinit var imageBytes: ByteArray
    private val downloadedImageBytes = java.util.concurrent.atomic.AtomicLong()

    @Before fun prepare() {
        previous = prefs.all.toMap()
        // Real, decodable high-entropy pixels keep the PNG above the former
        // 1 MiB cap without relying on padding that a decoder may ignore.
        val bitmap = android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val random = java.util.Random(731L)
        bitmap.setPixels(IntArray(640 * 640) { 0xFF000000.toInt() or random.nextInt(0x1000000) }, 0, 640, 0, 0, 640, 640)
        imageBytes = java.io.ByteArrayOutputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        assertTrue("The image fixture must exercise a valid image larger than 1 MiB", imageBytes.size > 1024 * 1024 && imageBytes.size <= 4 * 1024 * 1024)
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app)
        prefs.edit().clear().putString("endpoint", "").putBoolean("announcements", false).commit()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.requestUrl!!.encodedPath
                val signed = request.getHeader("Authorization") == "Bearer channel-fixture"
                if (path.startsWith("/api/messages") && !signed) return json("{}", 401)
                if (path == "/api/assistant/memory") {
                    check(releaseMemory.await(60, TimeUnit.SECONDS))
                    if (!memoryRecovered) return json("""{"error":{"code":"memory_fixture","message":"记忆资料暂不可用","eventId":"fixture-memory"}}""", 503)
                    return json("""{"enabled":true,"note":"已恢复的个人备注"}""")
                }
                if (path == "/api/messages/media/image-fixture") return MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(imageBytes))
                if (path == "/api/messages" && request.method == "POST") {
                    sentText = JSONObject(request.body.readUtf8()).getString("text")
                    return json("""{"ok":true}""")
                }
                if (path == "/api/messages/read") {
                    val body = JSONObject(request.body.readUtf8())
                    if (body.optString("channel") != "public" || body.has("throughMessageId")) return json("{}", 422)
                    body.getJSONArray("messageIds").let { ids -> (0 until ids.length()).forEach { readIds.add(ids.getString(it)) } }
                    return json("""{"ok":true,"channel":"public","unreadCount":${45 - readIds.size}}""")
                }
                val body = when (path) {
                    "/api/site" -> """{"buildings":[{"code":"test","name":"测试楼"}]}"""
                    "/api/auth/me" -> """{"user":{"id":"channel-user","nick":"测试同学","dormBuilding":"test"}}"""
                    "/api/user/permissions" -> """{"permissions":{"tasks":{"view":true},"queue":{"view":false}}}"""
                    "/api/tasks" -> { tasksStarted.countDown(); releaseTasks.await(30, TimeUnit.SECONDS); """{"tasks":[]}""" }
                    "/api/machines" -> """{"board":{"dormBuilding":"test","floors":[]}}"""
                    "/api/user/announcements" -> """{"enabled":false}"""
                    "/api/assistant/status" -> """{"ready":true,"dormBuilding":"test","buildingName":"测试楼","usage":{"dailyRemaining":100,"dailyLimit":1000,"minuteRemaining":30}}"""
                    "/api/assistant/history" -> """{"messages":[{"role":"assistant","content":"可以帮你查询当前楼栋的空闲机器。"}]}"""
                    "/api/messages/realtime" -> """{"enabled":false}"""
                    "/api/messages/channels" -> """{"channels":[{"code":"public","name":"公共频道","unreadCount":${45 - readIds.size},"summary":{"messageId":"message-0","nick":"李**同学","text":"你好","kind":"text","at":"2030-01-01T00:00:00Z"}}]}"""
                    "/api/messages" -> {
                        val rows = (0 until 45).map { index ->
                            JSONObject().put("id", if (index == 0 && replaceVisibleMessage) "replacement-0" else "message-$index")
                                .put("nick", "李**同学").put("text", "这是第 $index 条可见性测试消息，只有实际出现在屏幕上才算已读。")
                                .also { if (index == 0) it.put("media", JSONObject().put("id", "image-fixture").put("kind", "image")) }
                        }.toMutableList()
                        if (sentText.isNotBlank()) rows.add(0, JSONObject().put("id", "sent-message").put("text", sentText).put("nick", "测试同学").put("mine", true))
                        JSONObject().put("messages", JSONArray(rows)).toString()
                    }
                    else -> "{}"
                }
                return json(body)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .readTimeout(65, TimeUnit.SECONDS)
            .eventListener(object : okhttp3.EventListener() {
                override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
                    if (call.request().url.encodedPath == "/api/messages/media/image-fixture") downloadedImageBytes.set(byteCount)
                }
            })
            .addInterceptor { chain -> check(chain.request().url.host == "127.0.0.1" && chain.request().url.port == server.port); chain.proceed(chain.request()) }
            .followRedirects(false).followSslRedirects(false).build()
    }

    @After fun cleanup() {
        releaseTasks.countDown(); releaseMemory.countDown()
        onMain { models.clear() }
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app)
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); server.shutdown()
        prefs.edit().clear().apply { previous.forEach { (key, value) -> when (value) {
            is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value)
        } } }.commit()
    }

    @Test fun directoryAppearsBeforeSlowPersonalRefreshAndOnlyVisibleMessagesBecomeRead() {
        // MockWebServer.url() uses the address's canonical host name, which is
        // localhost on Android even when start() bound 127.0.0.1. Keep the URL,
        // certificate SAN and no-network-escape interceptor on the same literal IP.
        val origin = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        val vm = onMain { AppViewModel(app).also {
            models.put("channels", it)
            it.api.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(it.api, client)
            it.configure(origin)
        } }
        compose.waitUntil(15_000) { vm.state.value.endpoint == origin && !vm.state.value.loading && !vm.state.value.busy }
        assertNull("The fixture must complete its real TLS site request before login", vm.state.value.error)
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/api/site" })
        onMain { vm.api.saveSession(JSONObject().put("accessToken", "channel-fixture").put("expiresIn", 900)); vm.platformSessionChanged() }
        compose.waitUntil(10_000) { vm.state.value.profile?.id == "channel-user" }
        onMain { vm.tab(2) }
        var showWholeApp by mutableStateOf(false)
        compose.setContent {
            val state by vm.state.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") { Box(Modifier.width(320.dp)) {
                    if (showWholeApp) WeShirtApp(vm, {}, {}) else ChannelScreen(state, vm, {})
                } }
            }
        }
        compose.waitUntil(10_000) { vm.state.value.channels.isNotEmpty() && tasksStarted.count == 0L }
        compose.onNodeWithTag("channel-directory").assertIsDisplayed()
        assertEquals("The real task request must still be blocked when the directory is displayed", 1L, releaseTasks.count)
        releaseTasks.countDown()
        assertTrue("The authenticated directory must preserve its real unread count", vm.state.value.channels.single().unreadCount > 0)
        captureDirectoryEvidence()
        compose.onNodeWithText("李**同学:你好").assertIsDisplayed()
        // Clickable Card merges its children's descriptions and text. Inspect
        // the actual badge layout node rather than searching that merged card.
        val unread = compose.onNodeWithTag("channel-unread:public", useUnmergedTree = true)
            .assertIsDisplayed().assertHeightIsAtLeast(16.dp)
        val badgeBounds = unread.getUnclippedBoundsInRoot()
        val cardBounds = compose.onNodeWithTag("channel:public").getUnclippedBoundsInRoot()
        val previewBounds = compose.onNodeWithTag("channel-summary:public", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Unread badge must have real width", badgeBounds.right > badgeBounds.left)
        assertTrue("Unread badge must remain to the right of the summary", badgeBounds.left >= previewBounds.right)
        assertTrue("Unread badge must stay inside the visible card", badgeBounds.left >= cardBounds.left && badgeBounds.right <= cardBounds.right && badgeBounds.top >= cardBounds.top && badgeBounds.bottom <= cardBounds.bottom)
        assertFalse("The directory must neither prefetch message bodies nor clear unread", requests.any { it.requestUrl!!.encodedPath in setOf("/api/messages", "/api/messages/read") })
        compose.onNodeWithTag("channel:public").performClick()
        compose.waitUntil(10_000) { readIds.isNotEmpty() }
        assertTrue("Only the native viewport can acknowledge messages", readIds.size in 1..15)
        assertFalse("An off-screen older message must remain unread", "message-44" in readIds)
        assertTrue(requests.filter { it.requestUrl!!.encodedPath.startsWith("/api/messages") }.all { it.getHeader("Authorization") == "Bearer channel-fixture" })
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("channel-image:message-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("channel-image:message-0", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("The whole image body above 1 MiB must be downloaded before decoding", imageBytes.size.toLong(), downloadedImageBytes.get())
        assertTrue("Images load through the authenticated attachment API without a user tap", requests.any { it.requestUrl!!.encodedPath == "/api/messages/media/image-fixture" && it.getHeader("Authorization") == "Bearer channel-fixture" })
        compose.onNodeWithText("点击查看图片").assertDoesNotExist()
        captureScreen("native-channel-inline-image")
        // The composer panel stays in the same Activity; rows must not become read while it is open.
        compose.onNodeWithTag("channel-attachments-toggle").performClick()
        compose.onNodeWithTag("channel-attachment-panel").assertIsDisplayed()
        compose.onNodeWithText("图库").assertIsDisplayed()
        compose.onNodeWithText("拍照").assertIsDisplayed()
        compose.onNodeWithText("视频 · 30秒内").assertIsDisplayed()
        compose.onNodeWithText("文件").assertIsDisplayed()
        captureScreen("native-channel-composer-panel")
        val beforeModal = readIds.toSet()
        replaceVisibleMessage = true
        onMain { vm.refreshMessages() }
        compose.waitUntil(10_000) { vm.state.value.messages.any { it.id == "replacement-0" } }
        android.os.SystemClock.sleep(750)
        assertEquals("Opening the composer panel must not acknowledge newly arriving messages", beforeModal, readIds.toSet())
        compose.onNodeWithTag("channel-attachments-close").performClick()
        compose.onNodeWithTag("channel-message-list").performScrollToIndex(0)
        compose.waitUntil(10_000) { "replacement-0" in readIds }

        // With no relay, a successful POST must appear immediately, before the 30-second fallback.
        compose.onNode(hasSetTextAction()).performTextInput("原生发送即时刷新")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(3_000) { vm.state.value.messages.any { it.mine && it.text == "原生发送即时刷新" } }
        assertEquals("原生发送即时刷新", sentText)
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/api/messages" && it.method == "POST" && it.getHeader("Idempotency-Key") != null })
        compose.onNodeWithText("所有频道").performClick()
        compose.onNodeWithTag("channel-directory").assertIsDisplayed()
        assertTrue("Unread older messages remain visible on return", vm.state.value.channels.single().unreadCount > 0)
        compose.runOnIdle { showWholeApp = true }
        compose.waitUntil(10_000) { !vm.state.value.loading }
        compose.waitForIdle()
        // The first full-app composition may offer notification permission.
        // Follow the real opt-out path; runs after an earlier offer need no action.
        if (compose.onAllNodesWithText("暂不开启").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("暂不开启").assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("navigation:4").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { requests.any { it.requestUrl!!.encodedPath == "/api/assistant/status" } }
        compose.onNodeWithTag("assistant-page").assertIsDisplayed()
        compose.onNodeWithTag("navigation:4").assertIsSelected()
        compose.waitUntil(10_000) { vm.state.value.assistant.panels["history"]?.data != null }
        compose.onNodeWithTag("assistant-status-card").assertIsDisplayed()
        compose.onNodeWithTag("assistant-message:0").assertIsDisplayed()
        assertEquals("The state card and conversation must not wait for optional memory", 1L, releaseMemory.count)
        assertEquals("Opening the assistant must join the existing background preload", 1, requests.count { it.requestUrl!!.encodedPath == "/api/assistant/memory" })
        assertTrue("The independent assistant must use the current authenticated account", requests.filter { it.requestUrl!!.encodedPath.startsWith("/api/assistant/") }.all { it.getHeader("Authorization") == "Bearer channel-fixture" })
        captureScreen("native-assistant-page")
        releaseMemory.countDown()
        compose.waitUntil(10_000) { vm.state.value.assistant.panels["memory"]?.error != null }
        compose.onNodeWithTag("assistant-tab:2").performScrollTo().performClick()
        compose.onNodeWithTag("assistant-error:memory").assertIsDisplayed()
        val otherPanels = requests.count { it.requestUrl!!.encodedPath.startsWith("/api/assistant/") && it.requestUrl!!.encodedPath != "/api/assistant/memory" }
        memoryRecovered = true
        compose.onNodeWithTag("assistant-retry:memory").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.state.value.assistant.panels["memory"]?.data?.str("note") == "已恢复的个人备注" }
        compose.onNodeWithText("已恢复的个人备注").performScrollTo().assertIsDisplayed()
        assertEquals("A panel retry must not reload unrelated assistant panels", otherPanels, requests.count { it.requestUrl!!.encodedPath.startsWith("/api/assistant/") && it.requestUrl!!.encodedPath != "/api/assistant/memory" })
        captureScreen("native-assistant-partial-retry")
        compose.onNodeWithTag("navigation:2").performClick()
        compose.onNodeWithTag("channel-directory").assertIsDisplayed()
    }

    private fun captureDirectoryEvidence() {
        // Capture before assertions, so a layout regression keeps its visual
        // evidence even when the subsequent visibility/bounds checks fail.
        compose.onRoot().printToString().lineSequence().forEach { android.util.Log.i("ChannelInboxMerged", it) }
        compose.onRoot(useUnmergedTree = true).printToString().lineSequence().forEach { android.util.Log.i("ChannelInboxUnmerged", it) }
        captureScreen("native-channel-directory")
    }
    private fun captureScreen(name: String) {
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { image.writeToTestStorage(name) } finally { image.recycle() }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun <T> onMain(block: () -> T): T { val task = FutureTask(block); instrumentation.runOnMainSync(task); return task.get(10, TimeUnit.SECONDS) }
}
