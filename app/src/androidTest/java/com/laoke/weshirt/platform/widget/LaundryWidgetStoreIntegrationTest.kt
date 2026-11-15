package com.laoke.weshirt.platform.widget

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Building
import com.laoke.weshirt.data.LaundryTask
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.data.Profile
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.platform.PlatformCoordinator
import com.laoke.weshirt.platform.push.PushRuntime
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Actual encrypted scope, display preferences and foreground VM; no external endpoint is contacted. */
@RunWith(AndroidJUnit4::class)
class LaundryWidgetStoreIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val prefs get() = app.getSharedPreferences("preferences", 0)
    private val origin = "https://widget-store.invalid"
    private val models = ViewModelStore()
    private val boardRelease = CountDownLatch(1)
    private val occupancyRelease = CountDownLatch(1)
    private var previous = emptyMap<String, Any?>()
    private var http: OkHttpClient? = null

    @Before fun prepare() {
        previous = prefs.all.toMap()
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app); LaundryWidgetRefresh.cancel(app)
        prefs.edit().clear().putString("endpoint", origin).putString("building", "test").putBoolean("announcements", false).commit()
        signIn()
    }

    @After fun cleanup() {
        boardRelease.countDown(); occupancyRelease.countDown()
        onMain { models.clear() }
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app); LaundryWidgetRefresh.cancel(app)
        http?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        app.getSharedPreferences("native_task_revisions", 0).edit().clear().commit()
        prefs.edit().clear().apply { previous.forEach { (key, value) -> when(value) {
            is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
            is Long -> putLong(key, value); is Float -> putFloat(key, value)
        } } }.commit()
    }

    private fun signIn(user: String = "widget-user") {
        SecureSession(app).save(JSONObject().put("origin", origin).put("accessToken", "ma1.$user.fixture")
            .put("refreshToken", "unused-fixture").put("expiresAt", System.currentTimeMillis() + 600_000)
            .put("user", JSONObject().put("id", user)))
    }

    @Test fun lateSameScopeResponseIsRejectedAndPermissionRedactionStillErasesEveryTaskField() {
        val now = System.currentTimeMillis()
        val scope = requireNotNull(LaundryWidgetStore.captureScope(app))
        val current = LaundryWidgetSnapshot("测试楼", 2, 1, 0, now, "1号洗衣机", now + 600_000,
            now - 300_000, "task-current", "washer")
        assertTrue(LaundryWidgetStore.update(app, current, scope))
        assertFalse(LaundryWidgetStore.update(app, current.copy(updatedAtMillis = now - 1,
            taskId = "obsolete", taskMachineName = "旧机位"), scope))
        assertEquals(current, LaundryWidgetStore.read(app))
        assertTrue(LaundryWidgetStore.redactTask(app, scope))
        val redacted = requireNotNull(LaundryWidgetStore.read(app))
        assertEquals(now, redacted.updatedAtMillis)
        assertNull(redacted.taskId); assertNull(redacted.taskMachineName); assertNull(redacted.taskKind)
        assertNull(redacted.taskEstimatedFinishAtMillis); assertNull(redacted.taskStartedAtMillis)
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(redacted, now))
        assertFalse(LaundryWidgetStore.update(app, current.copy(updatedAtMillis = now - 2), scope))
        signIn("another-user")
        assertFalse(LaundryWidgetStore.update(app, current, scope)); assertNull(LaundryWidgetStore.read(app))
    }

    @Test fun widgetOnlySynchronizationPublishesWarningsAndRevocationsWithoutReminderScheduling() {
        val now = System.currentTimeMillis()
        val client = ApiClient(SecureSession(app), app.cacheDir).also { it.baseUrl = origin }
        val coordinator = PlatformCoordinator(app, SecureSession(app), client) { fail("Widget sync must not deliver push") }
        val task = LaundryTask("private-task", "machine-1", "旧名称", "washer", now + 600_000,
            false, "private note", false, false, false, startAt = now - 600_000)
        val state = AppState(endpoint = origin, building = "test", buildings = listOf(Building("test", "测试楼")),
            profile = Profile("widget-user", "测试用户", "", "test", 1, "user", notifyOn = true),
            access = JSONObject("""{"permissions":{"tasks":{"view":true}}}"""), tasks = listOf(task),
            machines = listOf(Machine("machine-1", "新名称", "2", "washer", "occupied", task.endAt, "", false, false, "")),
            updatedAt = now, tasksUpdatedAt = now)
        val reminders = app.getSharedPreferences("native_task_revisions", 0)
        reminders.edit().clear().putString("sentinel", "unchanged").commit()
        assertTrue(coordinator.synchronizeWidget(state))
        assertEquals("2 号洗衣机 · 新名称", LaundryWidgetStore.read(app)?.taskMachineName)
        assertTrue(coordinator.synchronizeWidget(state.copy(boardWarning = "状态待确认")))
        assertTrue(LaundryWidgetStore.read(app)!!.taskUnverified)
        assertTrue(coordinator.synchronizeWidget(state.copy(updatedAt = now - 30_000,
            access = JSONObject("""{"permissions":{"tasks":{"view":false}}}"""))))
        val redacted = LaundryWidgetStore.read(app)!!
        assertNull(redacted.taskId); assertTrue(redacted.taskUnverified); assertEquals(now, redacted.updatedAtMillis)
        assertEquals(mapOf("sentinel" to "unchanged"), reminders.all)
    }

    @Test fun delayedPublicBoardAndItsOccupancyFailureUpdateWidgetAfterAccountRefreshAlreadyFinished() {
        val now = System.currentTimeMillis()
        fun iso(value: Long) = Instant.ofEpochMilli(value).toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); check(request.url.host == "widget-store.invalid")
            var status = 200
            val body = when(request.url.encodedPath) {
                "/api/auth/me" -> """{"user":{"id":"widget-user","nick":"测试用户","dormBuilding":"test","notifyOn":false}}"""
                "/api/user/permissions" -> """{"permissions":{"tasks":{"view":true},"assistant":{"view":false}}}"""
                "/api/site" -> """{"buildings":[{"code":"test","name":"测试楼"}]}"""
                "/api/tasks" -> """{"serverNow":"${iso(now)}","tasks":[{"id":"vm-task","machineId":"machine-1","machineName":"任务旧名称","kind":"washer","startAt":"${iso(now - 600_000)}","endAt":"${iso(now + 600_000)}"}]}"""
                "/api/machines" -> {
                    check(boardRelease.await(20, TimeUnit.SECONDS))
                    """{"freshness":{"serverNow":"${iso(now)}"},"board":{"dormBuilding":"test","updatedAt":"${iso(now)}","floors":[{"code":"1","machines":[{"id":"machine-1","name":"公开新名称","slot":"2","state":"occupied"}]}]}}"""
                }
                "/api/occupancy" -> {
                    check(occupancyRelease.await(20, TimeUnit.SECONDS)); status = 503
                    """{"error":{"code":"fixture_unavailable","message":"公开登记暂不可用","eventId":"evt_widget_fixture"}}"""
                }
                "/api/user/announcements" -> """{"enabled":false}"""
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build().also { http = it }
        val vm = onMain { AppViewModel(app, client).also { models.put("widget", it) } }
        await("Account refresh must finish before the delayed room") { vm.state.value.tasksUpdatedAt != null && LaundryWidgetStore.read(app)?.taskId == "vm-task" }
        assertEquals("任务旧名称", LaundryWidgetStore.read(app)?.taskMachineName)
        boardRelease.countDown()
        await("The public board must publish its latest machine name before occupancy completes") {
            LaundryWidgetStore.read(app)?.taskMachineName == "2 号洗衣机 · 公开新名称"
        }
        assertFalse(LaundryWidgetStore.read(app)!!.taskUnverified)
        occupancyRelease.countDown()
        await("The final public occupancy failure must invalidate widget status") {
            vm.state.value.boardWarning != null && LaundryWidgetStore.read(app)?.taskUnverified == true
        }
        assertEquals("vm-task", LaundryWidgetStore.read(app)?.taskId)
    }

    private fun await(message: String, ready: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!ready() && System.nanoTime() < until) Thread.sleep(20)
        assertTrue(message, ready())
    }
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block); instrumentation.runOnMainSync(task); return task.get(5, TimeUnit.SECONDS)
    }
}
