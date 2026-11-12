package com.laoke.weshirt.ui.admin

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.platform.push.PushRuntime
import com.laoke.weshirt.ui.WeShirtTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
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
import java.util.concurrent.atomic.AtomicInteger

/** Real TLS, encrypted session, ViewModel refresh, API authorization and native admin navigation. */
@RunWith(AndroidJUnit4::class)
class NativeAdminIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val prefs get() = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val changedPreferences = listOf("endpoint", "building", "nativePushConsent", "telemetry", "announcements", "pendingGuestNotifyEnable")
    private var previous = emptyMap<String, Any?>()
    private val models = ViewModelStore()
    private val server = MockWebServer()
    private var client: OkHttpClient? = null
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val closes = AtomicInteger()
    @Volatile private var residentCanOperate = true

    @Before fun prepare() {
        check(BuildConfig.DEBUG) { "This fixture requires the debug-only endpoint override" }
        previous = changedPreferences.associateWith { prefs.all[it] }
        SecureSession(app).clear()
        prefs.edit().putString("endpoint", "").putString("building", "")
            .putBoolean("nativePushConsent", false).putBoolean("telemetry", false)
            .putBoolean("announcements", false).remove("pendingGuestNotifyEnable").commit()
        PushRuntime.clearLocalSession(app)

        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                if (request.method != "GET") return json("{}", 405)
                val path = request.requestUrl!!.encodedPath
                if (path.startsWith("/api/admin/") && request.getHeader("Authorization") != "Bearer admin-fixture-access") return json("{}", 401)
                val body = when (path) {
                    "/api/site" -> """{"buildings":[{"code":"test","name":"测试楼"}]}"""
                    "/api/auth/me" -> """{"user":{"id":"admin-fixture","nick":"测试管理员","dormBuilding":"test","role":"admin"}}"""
                    "/api/user/permissions" -> """{"adminVisible":true,"identity":{"role":"admin"},"permissions":{"adminUsers":{"view":true,"operate":true,"viewBuildings":["test"],"operateBuildings":["test"]},"tasks":{"view":false,"operate":false},"queue":{"view":false,"operate":false},"messages":{"view":false,"operate":false}}}"""
                    "/api/machines" -> """{"board":{"dormBuilding":"test","updatedAt":"2030-01-01T00:00:00Z","floors":[]},"freshness":{"stale":false,"syncFailed":false}}"""
                    // Match the API's per-target authority, not just the directory-level grant.
                    "/api/admin/users" -> """{"bulk":{"max":50,"operate":true,"operateBuildings":["test"]},"items":[{"id":"resident-fixture","nick":"测试居民","dormBuilding":"test","dingBound":true,"dingNick":"测试钉钉昵称","mobile":"138****0000","notifyOn":true,"canOperate":$residentCanOperate}]}"""
                    "/api/user/announcements" -> """{"enabled":false}"""
                    "/api/user/laundry-preferences" -> """{"preferences":{"enabled":false,"favorites":[],"timeWindows":[]}}"""
                    else -> "{}"
                }
                return json(body)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            // Fail locally before DNS/TLS if any path accidentally tries to escape this fixture.
            .addInterceptor { chain ->
                val url = chain.request().url
                check(url.isHttps && url.host in setOf("localhost", "127.0.0.1") && url.port == server.port) { "Unexpected non-fixture request" }
                chain.proceed(chain.request())
            }.connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }

    @After fun cleanup() {
        onMain { models.clear() }
        SecureSession(app).clear()
        PushRuntime.clearLocalSession(app)
        client?.dispatcher?.cancelAll()
        client?.connectionPool?.evictAll()
        client?.dispatcher?.executorService?.shutdownNow()
        server.shutdown()
        prefs.edit().apply {
            changedPreferences.forEach { key ->
                when (val value = previous[key]) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                }
            }
        }.commit()
    }

    @Test fun authorizedDirectoryLoadsRealUsersAndNativeBackReturnsThenCloses() {
        openAdminDirectory()
        compose.onNodeWithText("日常管理").assertIsDisplayed()
        compose.onNodeWithTag("admin-open:机器与机位").assertDoesNotExist()
        compose.onNodeWithTag("admin-open:网站与楼栋").assertDoesNotExist()
        compose.onNodeWithTag("admin-open:上游与系统").assertDoesNotExist()
        compose.onNodeWithTag("admin-open:成员功能权限与范围").assertDoesNotExist()
        openResident()
        compose.onNodeWithText("保存昵称与通知偏好").assertDoesNotExist()
        compose.onNodeWithTag("admin-user-expand").performScrollTo().performClick()
        compose.onNodeWithContentDescription("复制用户 ID").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("昵称") and hasSetTextAction()).performScrollTo().performTextClearance()
        compose.onNode(hasText("昵称") and hasSetTextAction()).performTextInput("更新的测试昵称")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("保存昵称与通知偏好").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("删除用户").performScrollTo().assertIsDisplayed()
        assertReadOnlyAuthenticatedRequests()

        Espresso.pressBack()
        compose.onNodeWithTag("admin-open:用户管理").assertIsDisplayed()
        assertEquals("The first native back must return to the directory", 0, closes.get())
        Espresso.pressBack()
        compose.onNodeWithTag("admin-closed").assertIsDisplayed()
        assertEquals(1, closes.get())
    }

    @Test fun directoryOperationGrantCannotOverrideTheServersPerUserDenial() {
        residentCanOperate = false
        openAdminDirectory()
        openResident()
        compose.onNodeWithTag("admin-user-expand").performScrollTo().performClick()
        compose.onNodeWithContentDescription("复制用户 ID").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("昵称") and hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("保存昵称与通知偏好").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("删除用户").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        assertReadOnlyAuthenticatedRequests()
    }

    private fun openAdminDirectory() {
        val origin = server.url("/").toString().trimEnd('/')
        val vm = onMain {
            AppViewModel(app).also { model ->
                models.put("admin-integration", model)
                model.api.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(model.api, requireNotNull(client))
                model.configure(origin)
            }
        }
        compose.waitUntil(15_000) { vm.state.value.endpoint == origin && !vm.state.value.loading && !vm.state.value.busy }
        onMain {
            vm.api.saveSession(JSONObject().put("accessToken", "admin-fixture-access")
                .put("refreshToken", "admin-fixture-refresh").put("expiresIn", 900))
            vm.platformSessionChanged()
        }
        compose.waitUntil(15_000) { vm.state.value.profile?.id == "admin-fixture" && vm.state.value.access.optBoolean("adminVisible") && !vm.state.value.loading }
        assertNull("The fixture must load real profile and permissions without refresh errors", vm.state.value.error)
        assertEquals(origin, vm.session.read()!!.getString("origin"))
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/api/user/permissions" && it.getHeader("Authorization") == "Bearer admin-fixture-access" })

        compose.setContent {
            val state by vm.state.collectAsState()
            var open by remember { mutableStateOf(true) }
            WeShirtTheme("light", "blue") {
                if (open) NativeAdminScreen(state, vm) { closes.incrementAndGet(); open = false }
                else Text("后台已关闭", Modifier.testTag("admin-closed"))
            }
        }
    }

    private fun openResident() {
        compose.onNodeWithTag("admin-open:用户管理").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { requests.any { it.requestUrl!!.encodedPath == "/api/admin/users" } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("测试居民").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试楼 · 已绑定钉钉").performScrollTo().assertIsDisplayed()
    }

    private fun assertReadOnlyAuthenticatedRequests() {
        val users = requests.single { it.requestUrl!!.encodedPath == "/api/admin/users" }
        assertEquals("Bearer admin-fixture-access", users.getHeader("Authorization"))
        assertTrue("Browsing and editing a draft must never submit a mutation", requests.all { it.method == "GET" })
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setHeader("Cache-Control", "no-store").setBody(body)

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
