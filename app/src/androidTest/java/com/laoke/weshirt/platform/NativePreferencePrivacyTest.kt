package com.laoke.weshirt.platform

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Profile
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.LaundryReminders
import com.laoke.weshirt.platform.notification.TaskProgress
import com.laoke.weshirt.platform.push.NativePushEvent
import com.laoke.weshirt.platform.push.PushConfiguration
import com.laoke.weshirt.platform.push.PushEventKind
import com.laoke.weshirt.platform.push.PushRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Exercises the real ViewModel, Android preferences, notification service and WorkManager. */
@RunWith(AndroidJUnit4::class)
class NativePreferencePrivacyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val prefs get() = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val store = ViewModelStore()

    @Before fun prepare() {
        runBlocking { PushRuntime.logout(app) }
        SecureSession(app).clear()
        prefs.edit().putString("endpoint", "").putBoolean("nativePushConsent", false).putBoolean("announcements", true).commit()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    }

    @After fun cleanup() {
        onMain { store.clear() }
        runBlocking { PushRuntime.logout(app) }
        SecureSession(app).clear()
        prefs.edit().remove("endpoint").remove("nativePushConsent").remove("announcements").commit()
    }

    @Test fun disablingWithoutAServiceRevokesLocallyBeforeTheConfigurationError() {
        val vm = newViewModel()
        seedActivePreferences(vm)
        val jobs = onMain {
            listOf(vm.toggleNotify(false), vm.toggleAnnouncements(false)).also { assertLocallyDisabled(vm) }
        }
        runBlocking { jobs.joinAll() }
        assertLocallyDisabled(vm)
        assertTrue("The actual API configuration failure retains its status", vm.state.value.error.orEmpty().contains("状态码 0"))
        assertTrue("The actual API configuration failure retains its event ID", vm.state.value.error.orEmpty().contains("事件 "))
        awaitCancelledWork()
        val recreated = newViewModel()
        assertFalse("A fresh ViewModel must load the local announcement opt-out", recreated.state.value.announcementsOn)
        assertFalse(recreated.state.value.notificationsOn)
    }

    @Test fun revocationBypassesABusyMutationAndSupersedesQueuedEnabling() {
        val vm = newViewModel()
        seedActivePreferences(vm)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = onMain { vm.mutate(null, wait = true) { entered.complete(Unit); release.await() } }
        runBlocking { entered.await() }
        try {
            val jobs = onMain {
                val queued = listOf(vm.toggleNotify(true), vm.toggleAnnouncements(true))
                val revoked = listOf(vm.toggleNotify(false), vm.toggleAnnouncements(false))
                assertTrue(vm.state.value.busy)
                assertLocallyDisabled(vm)
                queued + revoked
            }
            release.complete(Unit)
            runBlocking { blocker.join(); jobs.joinAll() }
            assertLocallyDisabled(vm)
            awaitCancelledWork()
        } finally { release.complete(Unit) }
    }

    @Test fun lateSuccessfulEnableAndServerRefreshCannotUndoANewerOptOut() {
        for (path in listOf("/api/user/notify", "/api/user/announcements")) {
            val vm = newViewModel()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val serverPreferenceRead = CountDownLatch(1)
            val origin = "https://example.invalid"
            onMain {
                vm.api.baseUrl = origin
                vm.session.save(JSONObject().put("origin", origin).put("accessToken", "instrumented-access")
                    .put("expiresAt", System.currentTimeMillis() + 600_000))
                seedState(vm, profile = Profile("privacy-test", "测试用户", "", "test", 1, "user"))
                // Replace only the HTTP transport. ApiClient still performs its real auth,
                // request, response, generation and error handling against this local fixture.
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.encodedPath == path && request.method != "GET") {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Test did not release the in-flight API response" }
                    }
                    if (request.url.encodedPath == "/api/user/announcements" && request.method == "GET") serverPreferenceRead.countDown()
                    val body = when (request.url.encodedPath) {
                        "/api/site" -> "{\"buildings\":[{\"code\":\"test\",\"name\":\"测试楼\"}]}"
                        "/api/auth/me" -> "{\"user\":{\"id\":\"privacy-test\",\"nick\":\"测试用户\",\"dormBuilding\":\"test\"}}"
                        "/api/user/announcements" -> "{\"enabled\":true}"
                        "/api/announcements" -> "{\"announcements\":[{\"id\":\"late\",\"version\":\"1\",\"title\":\"晚到公告\"}]}"
                        else -> "{}"
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
                vm.api.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(vm.api, client)
            }
            val enabling = onMain { if (path.endsWith("notify")) vm.toggleNotify(true) else vm.toggleAnnouncements(true) }
            try {
                assertTrue("The enable request must actually enter the HTTP transport", entered.await(10, TimeUnit.SECONDS))
                val disabling = onMain { if (path.endsWith("notify")) vm.toggleNotify(false) else vm.toggleAnnouncements(false) }
                assertFalse(prefs.getBoolean(if (path.endsWith("notify")) "nativePushConsent" else "announcements", true))
                release.countDown()
                runBlocking { enabling.join(); disabling.join(); onMain { vm.refresh(forceMetadata = true) }.join() }
                assertTrue("A real refresh must read enabled=true from the server fixture", serverPreferenceRead.await(10, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (vm.state.value.loading && System.nanoTime() < deadline) Thread.sleep(30)
                assertFalse("The server refresh must finish before checking the preference", vm.state.value.loading)
                if (path.endsWith("notify")) {
                    assertFalse(vm.state.value.notificationsOn)
                    assertFalse(prefs.getBoolean("nativePushConsent", true))
                } else {
                    assertFalse("Server enabled=true must respect the newer local opt-out", vm.state.value.announcementsOn)
                    assertTrue(vm.state.value.announcements.isEmpty())
                    assertFalse(prefs.getBoolean("announcements", true))
                }
            } finally { release.countDown(); onMain { store.clear() }; SecureSession(app).clear() }
        }
    }

    private fun newViewModel(): AppViewModel = onMain {
        AppViewModel(app).also { store.put("privacy", it) }
    }

    private fun seedActivePreferences(vm: AppViewModel) {
        prefs.edit().putBoolean("nativePushConsent", true).putBoolean("announcements", true).commit()
        PushRuntime.initialize(app, PushConfiguration(consent = true))
        PushRuntime.onSessionAvailable(app)
        assertTrue(LaundryNotifications(app).post(NativePushEvent("privacy-fixture", 1, PushEventKind.ANNOUNCEMENT, "测试通知", "用于验证撤回")))
        LaundryReminders.schedule(app, TaskProgress("privacy-reminder", 1, "测试机器", System.currentTimeMillis(), System.currentTimeMillis() + 600_000))
        onMain { seedState(vm, Profile("privacy-test", "测试用户", "", "test", 1, "user"), active = true) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedState(vm: AppViewModel, profile: Profile, active: Boolean = false) {
        val state = AppViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>
        state.value = state.value.copy(profile = profile, notificationsOn = active, announcementsOn = true,
            announcements = if (active) listOf(JSONObject().put("id", "visible").put("version", "1")) else emptyList())
    }

    private fun assertLocallyDisabled(vm: AppViewModel) {
        assertFalse(prefs.getBoolean("nativePushConsent", true))
        assertFalse(prefs.getBoolean("announcements", true))
        assertFalse(vm.state.value.notificationsOn)
        assertFalse(vm.state.value.announcementsOn)
        assertTrue(vm.state.value.announcements.isEmpty())
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        assertFalse(PushRuntime.notificationsAllowed(app))
        assertTrue(app.getSharedPreferences(LaundryReminders.PREFS, Context.MODE_PRIVATE).all.isEmpty())
    }

    private fun awaitCancelledWork() {
        val work = WorkManager.getInstance(app)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (work.getWorkInfosByTag("laundry_reminder").get(5, TimeUnit.SECONDS).any { !it.state.isFinished } && System.nanoTime() < deadline) Thread.sleep(30)
        assertTrue(work.getWorkInfosByTag("laundry_reminder").get(5, TimeUnit.SECONDS).all { it.state.isFinished })
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
