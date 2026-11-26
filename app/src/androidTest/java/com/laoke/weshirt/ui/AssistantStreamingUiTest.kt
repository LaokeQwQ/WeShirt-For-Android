package com.laoke.weshirt.ui

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.platform.push.PushRuntime
import com.laoke.weshirt.ui.assistant.AssistantScreen
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real native rendering and ApiClient SSE reads; all HTTP responses are local fixtures. */
@RunWith(AndroidJUnit4::class)
class AssistantStreamingUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val prefs get() = app.getSharedPreferences("preferences", 0)
    private val models = ViewModelStore()
    private val release = CountDownLatch(1)
    private val streamPosts = AtomicInteger()
    private val completed = AtomicBoolean()
    private lateinit var vm: AppViewModel
    private lateinit var client: OkHttpClient
    private var previous = emptyMap<String, Any?>()
    private val finalHistory = """[{"role":"user","content":"哪台空闲？"},{"role":"assistant","content":"先看看当前楼栋。"}]"""

    @Before fun prepare() {
        previous = prefs.all.toMap()
        prefs.edit().clear().putString("endpoint", "").putBoolean("announcements", false).commit()
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app)
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body: ResponseBody = if (path == "/api/assistant/chat/stream") {
                assertEquals("Bearer streaming-ui-access", request.header("Authorization"))
                assertEquals("text/event-stream", request.header("Accept"))
                streamPosts.incrementAndGet()
                val prefix = Buffer().writeUtf8("event: delta\ndata: {\"text\":\"先看看\"}\n\n")
                val suffix = Buffer().writeUtf8("event: delta\ndata: {\"text\":\"当前楼栋。\"}\n\nevent: done\ndata: {\"reply\":\"先看看当前楼栋。\",\"messages\":$finalHistory,\"usage\":{\"dailyRemaining\":99,\"dailyLimit\":100,\"minuteRemaining\":9}}\n\n")
                object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    private val source = object : Source {
                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (prefix.size > 0) return prefix.read(sink, byteCount)
                            check(release.await(15, TimeUnit.SECONDS)) { "The test did not release the provider" }
                            completed.set(true)
                            return suffix.read(sink, byteCount)
                        }
                    }.buffer()
                    override fun source() = source
                }
            } else {
                val data = when (path) {
                    "/api/site" -> """{"buildings":[{"code":"test","name":"测试楼"}]}"""
                    "/api/auth/me" -> """{"user":{"id":"stream-user","nick":"测试同学","dormBuilding":"test"}}"""
                    "/api/user/permissions" -> """{"permissions":{"assistant":{"view":true},"tasks":{"view":false},"queue":{"view":false}}}"""
                    "/api/machines" -> """{"board":{"dormBuilding":"test","floors":[]}}"""
                    "/api/user/announcements" -> """{"enabled":false}"""
                    "/api/assistant/status" -> """{"ready":true,"dormBuilding":"test","buildingName":"测试楼","usage":{"dailyRemaining":100,"dailyLimit":100,"minuteRemaining":10}}"""
                    "/api/assistant/history" -> "{\"messages\":${if (completed.get()) finalHistory else "[]"}}"
                    else -> "{}"
                }
                data.toResponseBody("application/json".toMediaType())
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        vm = onMain { AppViewModel(app, client).also { models.put("assistant", it); it.configure("https://streaming-ui.invalid") } }
        compose.waitUntil(10_000) { vm.state.value.endpoint == "https://streaming-ui.invalid" && !vm.state.value.busy }
        onMain {
            vm.api.saveSession(JSONObject().put("accessToken", "streaming-ui-access").put("refreshToken", "streaming-ui-refresh").put("expiresIn", 900))
            vm.platformSessionChanged(); vm.tab(4)
        }
        compose.waitUntil(10_000) { vm.state.value.authReady && vm.state.value.profile?.id == "stream-user" && vm.state.value.assistant.panels["status"]?.data != null }
        compose.setContent { val state by vm.state.collectAsState(); WeShirtTheme("light", "blue") { AssistantScreen(state, vm, {}) } }
    }

    @After fun cleanup() {
        release.countDown(); onMain { models.clear() }
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app)
        prefs.edit().clear().apply { previous.forEach { (key, value) -> when (value) {
            is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value)
        } } }.commit()
    }

    @Test fun firstNativeTextIsVisibleBeforeCompletionThenCanonicalHistoryReplacesTheDraft() {
        send()
        compose.onNodeWithTag("assistant-conversation").performScrollToNode(hasTestTag("assistant-stream-answer"))
        compose.waitUntil(10_000) { visibleNativeText("先看看") }
        assertFalse("First text must not wait for final provider data", completed.get())
        assertTrue(vm.state.value.assistant.panels["history"]?.data?.optJSONArray("messages")?.length() == 0)
        release.countDown()
        compose.waitUntil(10_000) { vm.state.value.assistant.panels["history"]?.data?.optJSONArray("messages")?.length() == 2 }
        compose.onNodeWithTag("assistant-stream-answer").assertDoesNotExist()
        assertEquals(1, streamPosts.get())
    }

    @Test fun stoppingKeepsPartialTextAndTypedQuestionWithoutReplayingOrConfirmingHistory() {
        send()
        compose.onNodeWithTag("assistant-conversation").performScrollToNode(hasTestTag("assistant-stream-answer"))
        compose.waitUntil(10_000) { visibleNativeText("先看看") }
        compose.onNodeWithTag("assistant-stop-stream").performClick()
        release.countDown()
        compose.onNodeWithTag("assistant-conversation").performScrollToNode(hasTestTag("assistant-chat-input"))
        compose.onNodeWithTag("assistant-chat-input").assertTextContains("哪台空闲？")
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("assistant-chat-send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("assistant-chat-send").assertIsEnabled()
        assertEquals(1, streamPosts.get())
        assertEquals(0, vm.state.value.assistant.panels["history"]?.data?.optJSONArray("messages")?.length())
    }

    private fun send() {
        compose.onNodeWithTag("assistant-conversation").performScrollToNode(hasTestTag("assistant-chat-input"))
        compose.onNodeWithTag("assistant-chat-input").performTextInput("哪台空闲？")
        compose.onNodeWithTag("assistant-chat-send").performScrollTo().performClick()
    }

    private fun visibleNativeText(text: String): Boolean = onMain {
        fun contains(view: View): Boolean {
            if (view is TextView && view.isShown && view.text.toString().contains(text)) return true
            return view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
        }
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { contains((it as Activity).window.decorView) }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block); instrumentation.runOnMainSync(task); return task.get(10, TimeUnit.SECONDS)
    }
}
