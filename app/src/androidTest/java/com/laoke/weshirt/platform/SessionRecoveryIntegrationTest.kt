package com.laoke.weshirt.platform

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.str
import com.laoke.weshirt.platform.push.PushRuntime
import com.laoke.weshirt.ui.SettingsScreen
import com.laoke.weshirt.ui.WeShirtTheme
import com.laoke.weshirt.ui.WeShirtApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.util.concurrent.CopyOnWriteArrayList

/** A cold ViewModel and the real settings UI; all HTTPS calls terminate in the fixture interceptor. */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val prefs get() = app.getSharedPreferences("preferences", 0)
    private val models = ViewModelStore()
    private val release = CountDownLatch(1)
    private val replacementBoardRelease = CountDownLatch(1)
    private val replacementBoardStarted = CountDownLatch(1)
    private val replacementIdentityRelease = CountDownLatch(1)
    private val replacementIdentityStarted = CountDownLatch(1)
    private var previous = emptyMap<String, Any?>()
    @Volatile private var profileStatus = 503
    @Volatile private var blockReplacementBoard = false
    @Volatile private var blockReplacementIdentity = false
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var client: OkHttpClient
    private val origin = "https://recovery.invalid"

    @Before fun prepare() {
        previous = prefs.all.toMap()
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app)
        prefs.edit().clear().putString("endpoint", origin).putString("building", "test").putBoolean("announcements", false).putBoolean("nativePushConsent", false).commit()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "recovery.invalid")
            val path = request.url.encodedPath
            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
            requests.add(path to buffer.readUtf8())
            var status = 200
            val body = when (path) {
                "/api/auth/me" -> {
                    if (request.header("Authorization") == "Bearer replacement-access") {
                        if (blockReplacementIdentity) { replacementIdentityStarted.countDown(); check(replacementIdentityRelease.await(20, TimeUnit.SECONDS)) }
                        """{"user":{"id":"replacement-user","nick":"替换账号","dormBuilding":"test"}}"""
                    } else {
                        check(request.header("Authorization") == "Bearer saved-access")
                        check(release.await(20, TimeUnit.SECONDS))
                        status = profileStatus
                        if (status == 200) """{"user":{"id":"saved-user","nick":"最新昵称","dormBuilding":"test"}}"""
                        else """{"error":{"code":"recovery_fixture","message":"账号暂不可用"}}"""
                    }
                }
                "/api/site" -> """{"buildings":[{"code":"test","name":"测试楼"}]}"""
                "/api/machines" -> {
                    if (blockReplacementBoard) { replacementBoardStarted.countDown(); check(replacementBoardRelease.await(20, TimeUnit.SECONDS)) }
                    """{"board":{"dormBuilding":"test","updatedAt":"2030-01-01T00:00:00Z","floors":[{"code":"1","machines":[{"id":"public-machine","name":"测试洗衣机","slot":"1","state":"idle"}]}]}}"""
                }
                "/api/user/permissions" -> """{"permissions":{"assistant":{"view":true}}}"""
                "/api/user/announcements" -> """{"enabled":false}"""
                "/api/user/test-notify" -> """{"ok":true,"accepted":true,"channel":"mobile","provider":"fcm"}"""
                "/api/session-fixture-expired" -> { status = 401; """{"error":{"code":"expired_fixture","message":"fixture expired"}}""" }
                else -> "{}"
            }
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    @After fun cleanup() {
        release.countDown(); replacementBoardRelease.countDown(); replacementIdentityRelease.countDown(); onMain { models.clear() }
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app)
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        prefs.edit().clear().apply { previous.forEach { (key, value) -> when (value) {
            is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
            is Long -> putLong(key, value); is Float -> putFloat(key, value)
        } } }.commit()
    }

    private fun saved(cached: Boolean) {
        val session = JSONObject().put("origin", origin).put("accessToken", "saved-access")
            .put("refreshToken", "saved-refresh").put("expiresAt", System.currentTimeMillis() + 600_000)
        if (cached) session.put("profile", JSONObject().put("id", "saved-user").put("nick", "缓存昵称").put("dormBuilding", "test"))
        SecureSession(app).save(session)
    }
    private fun model() = onMain { AppViewModel(app, client).also { models.put("recovery", it) } }
    private fun render(vm: AppViewModel) = compose.setContent {
        val state by vm.state.collectAsState()
        WeShirtTheme("light", "blue") { SettingsScreen(state, vm, {}, {}, {}) }
    }

    @Test fun coldRestoreNeverShowsLoginAndTransientFailureKeepsSavedIdentityAndCredentials() {
        saved(true)
        val vm = model()
        assertFalse(vm.state.value.authReady); assertTrue(vm.state.value.sessionRestoring)
        render(vm)
        compose.onNodeWithText("正在恢复账号").assertIsDisplayed()
        compose.onNodeWithText("钉钉登录").assertDoesNotExist()
        release.countDown()
        compose.waitUntil(10_000) { !vm.state.value.sessionRestoring }
        assertFalse("Cached display identity must not authorize private features after a failed restore", vm.state.value.authReady)
        assertEquals("缓存昵称", vm.state.value.profile?.nick)
        assertEquals("saved-access", SecureSession(app).read()?.str("accessToken"))
        assertNotNull(vm.state.value.authError)
        compose.onNodeWithText("钉钉登录").assertDoesNotExist()

        profileStatus = 200
        onMain { vm.refresh() }
        compose.waitUntil(10_000) { vm.state.value.profile?.nick == "最新昵称" && !vm.state.value.loading }
        assertNull(vm.state.value.authError)
        assertEquals("最新昵称", vm.api.cachedProfile()?.nick)
        // The current device is the sole test target; no DingTalk route or broad send.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        prefs.edit().putBoolean("nativePushConsent", true).commit()
        onMain { vm.testMobileNotification() }
        compose.waitUntil(10_000) { requests.any { it.first == "/api/user/test-notify" } && !vm.state.value.busy }
        val body = JSONObject(requests.single { it.first == "/api/user/test-notify" }.second)
        assertEquals("mobile", body.str("channel"))
        assertEquals(vm.session.installationId, body.str("installationId"))
        assertTrue(vm.state.value.info.orEmpty().contains("FCM"))
        assertTrue(vm.state.value.info.orEmpty().contains("不代表"))
    }

    @Test fun missingCachedProfileStaysNeutralAfterNetworkFailureAndOnlyConfirmed401ShowsLogin() {
        saved(false)
        val vm = model(); render(vm)
        compose.onNodeWithText("钉钉登录").assertDoesNotExist()
        release.countDown()
        compose.waitUntil(10_000) { !vm.state.value.sessionRestoring }
        assertFalse(vm.state.value.authReady)
        assertNotNull(SecureSession(app).read())
        compose.onNodeWithText("重试恢复账号").assertIsDisplayed()
        compose.onNodeWithText("钉钉登录").assertDoesNotExist()
        profileStatus = 401
        onMain { vm.refresh() }
        compose.waitUntil(10_000) { vm.state.value.authReady && SecureSession(app).read() == null }
        compose.onNodeWithText("钉钉登录").assertIsDisplayed()
        assertNull(vm.state.value.profile)
    }

    @Test fun rememberedPublicRoomLoadsWhileIdentityIsBlockedAndPrivateActionsWaitForVerification() {
        saved(true)
        val vm = model()
        compose.setContent { WeShirtApp(vm, {}, {}) }
        compose.waitUntil(10_000) { requests.any { it.first == "/api/machines" } && vm.state.value.machines.any { it.id == "public-machine" } && !vm.state.value.boardFromCache }
        assertEquals("test", vm.state.value.building)
        assertTrue(vm.state.value.sessionRestoring); assertFalse(vm.state.value.authReady)
        compose.onNodeWithText("测试洗衣机", substring = true).assertIsDisplayed()
        assertFalse(requests.any { it.first.startsWith("/api/assistant/") })
        onMain { vm.favorite(vm.state.value.machines.single()); vm.preloadAssistant(); vm.refreshMessages(); vm.refreshChannels() }
        compose.waitForIdle()
        assertFalse(requests.any { it.first == "/api/user/favorites/toggle" || it.first.startsWith("/api/assistant/") || it.first.startsWith("/api/messages") })
        compose.onNodeWithTag("navigation:4").performClick()
        compose.onNodeWithText("正在恢复账号").assertIsDisplayed()
        compose.onNodeWithText("钉钉登录").assertDoesNotExist()
        profileStatus = 200; release.countDown()
        compose.waitUntil(10_000) { vm.state.value.authReady && vm.state.value.assistant.panels["history"]?.data != null }
        assertEquals("saved-user", vm.state.value.assistant.scope?.userId)
        assertNotNull(SecureSession(app).read())
    }

    @Test fun replacementSessionRestoresBeforeOldIdentityReturnsAndOldFinallyCannotClearNewLoading() {
        saved(true)
        val vm = model()
        compose.waitUntil(10_000) { requests.any { it.first == "/api/auth/me" } && vm.state.value.machines.isNotEmpty() && !vm.state.value.loading }
        val oldPass = onMain { vm.refresh() }
        blockReplacementBoard = true
        onMain {
            vm.api.saveSession(JSONObject().put("accessToken", "replacement-access").put("refreshToken", "replacement-refresh").put("expiresIn", 600)
                .put("user", JSONObject().put("id", "replacement-user").put("nick", "替换账号").put("dormBuilding", "test")))
            vm.platformSessionChanged()
        }
        compose.waitUntil(5_000) { replacementBoardStarted.count == 0L && vm.state.value.authReady && vm.state.value.profile?.id == "replacement-user" }
        assertEquals("The old identity request is still blocked while the replacement is already verified", 1L, release.count)
        assertTrue("The new public board is deliberately unfinished", vm.state.value.loading)
        profileStatus = 401; release.countDown()
        compose.waitUntil(5_000) { oldPass.isCompleted }
        assertTrue("An old finally block must not clear the replacement board's loading flag", vm.state.value.loading)
        assertEquals("replacement-user", vm.state.value.profile?.id)
        assertEquals("replacement-access", SecureSession(app).read()?.str("accessToken"))
        assertTrue(vm.state.value.authReady)
        replacementBoardRelease.countDown()
        compose.waitUntil(5_000) { !vm.state.value.loading }
        assertEquals("replacement-user", vm.state.value.assistant.scope?.userId)
    }

    @Test fun backgroundInvalidationWaitsForMainAndQueuedOldFailureCannotClearReplacementSession() {
        saved(true); profileStatus = 200; release.countDown()
        val vm = model()
        compose.waitUntil(10_000) { vm.state.value.authReady && vm.state.value.profile?.id == "saved-user" && !vm.state.value.loading }
        blockReplacementIdentity = true
        onMain {
            // Holding Main until the IO call completes makes callback dispatch and
            // the later account fence deterministic, without any real network.
            val background = FutureTask { runBlocking(Dispatchers.IO) { runCatching { vm.api.call("/api/session-fixture-expired") } } }
            Thread(background, "session-invalidation-fixture").start()
            assertTrue(background.get(5, TimeUnit.SECONDS).isFailure)
            assertNull(SecureSession(app).read())
            assertEquals("IO must not mutate Main-owned caches before its dispatched callback runs", "saved-user", vm.state.value.profile?.id)
            vm.api.saveSession(JSONObject().put("accessToken", "replacement-access").put("refreshToken", "replacement-refresh").put("expiresIn", 600)
                .put("user", JSONObject().put("id", "replacement-user").put("nick", "替换账号").put("dormBuilding", "test")))
            vm.platformSessionChanged()
        }
        compose.waitUntil(5_000) { replacementIdentityStarted.count == 0L }
        compose.waitForIdle()
        assertEquals("The queued invalidation belongs to the old session", "replacement-user", vm.state.value.profile?.id)
        assertFalse(vm.state.value.authReady); assertTrue(vm.state.value.sessionRestoring)
        assertEquals("replacement-access", SecureSession(app).read()?.str("accessToken"))
        replacementIdentityRelease.countDown()
        compose.waitUntil(5_000) { vm.state.value.authReady && vm.state.value.profile?.id == "replacement-user" }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block); instrumentation.runOnMainSync(task); return task.get(10, TimeUnit.SECONDS)
    }
}
