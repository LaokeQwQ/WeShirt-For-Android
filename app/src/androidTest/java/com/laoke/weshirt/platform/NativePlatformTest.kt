package com.laoke.weshirt.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.MainActivity
import com.laoke.weshirt.platform.login.DingTalkLoginBridge
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.LaundryReminders
import com.laoke.weshirt.platform.notification.TaskProgress
import com.laoke.weshirt.platform.push.*
import com.laoke.weshirt.platform.widget.LaundryWidgetSnapshot
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs against real Android permission, notification, PackageManager and WorkManager services. */
@RunWith(AndroidJUnit4::class)
class NativePlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val notices get() = LaundryNotifications(app)

    @Before fun prepare() {
        runBlocking { PushRuntime.logout(app) }
        PushRuntime.initialize(app, PushConfiguration(consent = false))
    }

    @After fun cleanup() {
        runBlocking { PushRuntime.logout(app) }
        app.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().remove("nativePushConsent").commit()
    }

    @Test fun noConsentAndNoCredentialsKeepSdkEntryPointsDisabledAndDoNotNotify() {
        // Even explicit consent cannot invent a valid provider when no credentials were supplied.
        assertEquals(PushProvider.NONE, PushRuntime.initialize(app, PushConfiguration(consent = true)).provider)
        val components = listOf(
            "com.google.firebase.iid.FirebaseInstanceIdReceiver",
            "com.umeng.message.component.UmengMessageProvider",
            "com.taobao.accs.data.MsgDistributeService",
            "org.android.agoo.xiaomi.MiPushBroadcastReceiver",
            "com.xiaomi.mipush.sdk.PushMessageHandler",
            "com.huawei.hms.support.api.push.PushProvider",
            "com.huawei.hms.support.api.push.PushReceiver",
        )
        components.forEach { name ->
            assertEquals(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                app.packageManager.getComponentEnabledSetting(ComponentName(app.packageName, name)))
        }
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(notices.post(NativePushEvent("unsigned", 1, PushEventKind.ANNOUNCEMENT, "公告", "未登录不应展示")))
        assertTrue(manager.activeNotifications.isEmpty())
        assertMissingComponent("com.android.dingtalk.openauth.web.AuthWebviewActivity")
        assertMissingComponent("com.huawei.hms.aaid.InitProvider")
        assertMissingComponent("com.huawei.agconnect.core.provider.AGConnectInitializeProvider")
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("com.google.android.gms.permission.AD_ID"))
        assertFalse(requested.contains("freemme.permission.msa"))
    }

    @Test fun realNotificationDeliveryRejectsReplaysAndOlderRevisions() {
        grantNotifications()
        PushRuntime.onSessionAvailable(app)
        val id = "instrumented:${UUID.randomUUID()}"
        val event = NativePushEvent(id, 3, PushEventKind.ANNOUNCEMENT, "修订 3", "设备测试通知")
        assertTrue(notices.post(event))
        assertFalse(notices.post(event))
        assertFalse(notices.post(event.copy(revision = 2, title = "旧修订")))
        assertTrue(notices.post(event.copy(revision = 4, title = "修订 4")))
        val posted = manager.activeNotifications.single { it.tag == "event:$id" }
        assertEquals("修订 4", posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Persistence matters: a newly constructed delivery facade must also reject the replay.
        assertFalse(LaundryNotifications(app).post(event.copy(revision = 4)))
    }

    @Test fun logoutCancelsScheduledWorkAndErasesPrivateWidgetAndLoginState() = runBlocking {
        grantNotifications()
        PushRuntime.onSessionAvailable(app)
        val task = TaskProgress("instrumented-task", 1, "测试洗衣机", System.currentTimeMillis(), System.currentTimeMillis() + 600_000)
        LaundryReminders.schedule(app, task)
        assertTrue(notices.updateProgress(task))
        LaundryWidgetStore.update(app, LaundryWidgetSnapshot("测试楼", 2, 3, 0, System.currentTimeMillis(), "测试洗衣机", task.estimatedFinishAtMillis))
        DingTalkLoginBridge.createRequest(app, "instrumented-installation")
        assertTrue(app.getSharedPreferences("laundry_widget_snapshot", Context.MODE_PRIVATE).contains("snapshot"))
        assertTrue(WorkManager.getInstance(app).getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).any { !it.state.isFinished })

        PushRuntime.logout(app)

        assertFalse(PushRuntime.isSessionActive(app))
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(app.getSharedPreferences("laundry_widget_snapshot", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(app.getSharedPreferences("native_login_pending", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(app.getSharedPreferences("laundry_reminder_state", Context.MODE_PRIVATE).all.isEmpty())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (WorkManager.getInstance(app).getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).any { !it.state.isFinished } && System.nanoTime() < deadline) {
            Thread.sleep(50) // Cancellation is asynchronous inside WorkManager's real database executor.
        }
        assertTrue(WorkManager.getInstance(app).getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).all { it.state.isFinished })
    }

    @Test fun unconfiguredNativeColdStartOpensWithoutInitializingFirebase() {
        org.junit.Assume.assumeTrue("This case runs only in the public, unconfigured build", BuildConfig.API_BASE_URL.isBlank() && BuildConfig.PUSH_FCM_APP_ID.isBlank())
        app.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().remove("endpoint").putBoolean("nativePushConsent", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
                assertEquals(PushProvider.NONE, PushRuntime.route.provider)
                assertTrue(FirebaseApp.getApps(app).isEmpty())
                assertTrue(manager.activeNotifications.isEmpty())
            }
        }
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        }
        assertTrue("The emulator must allow notification permission", notices.capabilities().permissionGranted)
    }

    private fun assertMissingComponent(name: String) {
        val component = ComponentName(app.packageName, name)
        val info = runCatching { app.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        val provider = runCatching { app.packageManager.getProviderInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        assertNull(name, info)
        assertNull(name, provider)
    }
}
