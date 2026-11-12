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
    private val releaseTasks = java.util.concurrent.CountDownLatch(1)
    private val readIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var previous = emptyMap<String, Any?>()
    @Volatile private var replaceVisibleMessage = false
    @Volatile private var sentText = ""

    @Before fun prepare() {
        previous = prefs.all.toMap()
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
                    "/api/tasks" -> { releaseTasks.await(30, TimeUnit.SECONDS); """{"tasks":[]}""" }
                    "/api/machines" -> """{"board":{"dormBuilding":"test","floors":[]}}"""
                    "/api/user/announcements" -> """{"enabled":false}"""
                    "/api/messages/realtime" -> """{"enabled":false}"""
                    "/api/messages/channels" -> """{"channels":[{"code":"public","name":"公共频道","unreadCount":${45 - readIds.size},"summary":{"messageId":"message-0","nick":"李**同学","text":"你好","kind":"text","at":"2030-01-01T00:00:00Z"}}]}"""
                    "/api/messages" -> {
                        val rows = (0 until 45).map { index ->
                            JSONObject().put("id", if (index == 0 && replaceVisibleMessage) "replacement-0" else "message-$index")
                                .put("nick", "李**同学").put("text", "这是第 $index 条可见性测试消息，只有实际出现在屏幕上才算已读。")
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
            .addInterceptor { chain -> check(chain.request().url.host == "127.0.0.1" && chain.request().url.port == server.port); chain.proceed(chain.request()) }
            .followRedirects(false).followSslRedirects(false).build()
    }

    @After fun cleanup() {
        releaseTasks.countDown()
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
        compose.setContent {
            val state by vm.state.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") { Box(Modifier.width(320.dp)) { ChannelScreen(state, vm, {}) } }
            }
        }
        compose.waitUntil(10_000) { vm.state.value.channels.isNotEmpty() }
        assertTrue("Directory loading must not wait for the blocked tasks response", vm.state.value.loading)
        assertEquals(1L, releaseTasks.count)
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
        // An in-app modal leaves the Activity RESUMED; rows behind it still must not count.
        compose.onNodeWithContentDescription("添加图片、视频或拍摄").performClick()
        compose.onNodeWithText("添加附件").assertIsDisplayed()
        val beforeModal = readIds.toSet()
        replaceVisibleMessage = true
        onMain { vm.refreshMessages() }
        compose.waitUntil(10_000) { vm.state.value.messages.any { it.id == "replacement-0" } }
        android.os.SystemClock.sleep(750)
        assertEquals("A RESUMED Activity cannot acknowledge messages hidden by the attachment modal", beforeModal, readIds.toSet())
        compose.onNodeWithText("取消").performClick()
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
    }

    private fun captureDirectoryEvidence() {
        // Capture before assertions, so a layout regression keeps its visual
        // evidence even when the subsequent visibility/bounds checks fail.
        compose.onRoot().printToString().lineSequence().forEach { android.util.Log.i("ChannelInboxMerged", it) }
        compose.onRoot(useUnmergedTree = true).printToString().lineSequence().forEach { android.util.Log.i("ChannelInboxUnmerged", it) }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { image.writeToTestStorage("native-channel-directory") } finally { image.recycle() }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun <T> onMain(block: () -> T): T { val task = FutureTask(block); instrumentation.runOnMainSync(task); return task.get(10, TimeUnit.SECONDS) }
}
