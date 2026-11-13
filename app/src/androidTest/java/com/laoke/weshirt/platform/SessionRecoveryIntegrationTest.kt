package com.laoke.weshirt.platform

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.str
import com.laoke.weshirt.platform.push.PushRuntime
import com.laoke.weshirt.ui.SettingsScreen
import com.laoke.weshirt.ui.WeShirtTheme
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
    private var previous = emptyMap<String, Any?>()
    @Volatile private var profileStatus = 503
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var client: OkHttpClient
    private val origin = "https://recovery.invalid"

    @Before fun prepare() {
        previous = prefs.all.toMap()
        SecureSession(app).clear(); PushRuntime.clearLocalSession(app)
        prefs.edit().clear().putString("endpoint", origin).putBoolean("announcements", false).putBoolean("nativePushConsent", false).commit()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "recovery.invalid")
            val path = request.url.encodedPath
            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
            requests.add(path to buffer.readUtf8())
            var status = 200
            val body = when (path) {
                "/api/auth/me" -> {
                    check(request.header("Authorization") == "Bearer saved-access")
                    check(release.await(10, TimeUnit.SECONDS))
                    status = profileStatus
                    if (status == 200) """{"user":{"id":"saved-user","nick":"最新昵称","dormBuilding":"test"}}"""
                    else """{"error":{"code":"recovery_fixture","message":"账号暂不可用"}}"""
                }
                "/api/site" -> """{"buildings":[{"code":"test","name":"测试楼"}]}"""
                "/api/machines" -> """{"board":{"dormBuilding":"test","floors":[]}}"""
                "/api/user/announcements" -> """{"enabled":false}"""
                "/api/user/test-notify" -> """{"ok":true,"accepted":true,"channel":"mobile","provider":"fcm"}"""
                else -> "{}"
            }
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    @After fun cleanup() {
        release.countDown(); onMain { models.clear() }
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
        assertTrue(vm.state.value.authReady)
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

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block); instrumentation.runOnMainSync(task); return task.get(10, TimeUnit.SECONDS)
    }
}
