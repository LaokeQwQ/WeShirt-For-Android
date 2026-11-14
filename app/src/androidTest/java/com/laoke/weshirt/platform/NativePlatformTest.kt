package com.laoke.weshirt.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.FirebaseApp
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.MainActivity
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.serviceEndpoint
import com.laoke.weshirt.platform.login.DingTalkLoginBridge
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.LaundryReminders
import com.laoke.weshirt.platform.notification.TaskProgress
import com.laoke.weshirt.platform.push.*
import com.laoke.weshirt.platform.widget.LaundryWidgetSnapshot
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
        SecureSession(app).clear()
        runBlocking { PushRuntime.logout(app) }
        awaitNotifications("Notifications from the previous session must be cancelled") { it.isEmpty() }
        PushRuntime.initialize(app, PushConfiguration(consent = false))
    }

    @After fun cleanup() {
        runBlocking { PushRuntime.logout(app) }
        SecureSession(app).clear()
        awaitNotifications("Notifications must be cancelled after the test") { it.isEmpty() }
        app.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().remove("nativePushConsent").commit()
    }

    @Test fun noConsentAndNoCredentialsKeepSdkEntryPointsDisabledAndDoNotNotify() {
        // Even explicit consent cannot invent a valid provider when no credentials were supplied.
        assertEquals(PushProvider.NONE, PushRuntime.initialize(app, PushConfiguration(consent = true)).provider)
        val components = listOf(
            "com.google.firebase.iid.FirebaseInstanceIdReceiver",
            "com.umeng.message.component.UmengMessageProvider",
            "com.taobao.accs.data.MsgDistributeService",
        )
        components.forEach { name ->
            assertEquals(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                app.packageManager.getComponentEnabledSetting(ComponentName(app.packageName, name)))
        }
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(notices.post(NativePushEvent("unsigned", 1, PushEventKind.ANNOUNCEMENT, "公告", "未登录不应展示")))
        assertTrue(manager.activeNotifications.isEmpty())
        assertMissingComponent("com.android.dingtalk.openauth.web.AuthWebviewActivity")
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("com.google.android.gms.permission.AD_ID"))
        assertFalse(requested.contains("freemme.permission.msa"))
    }

    @Test fun packagedAppContainsOnlyFcmAndBaseUpushWithoutIndependentOemEntryPoints() {
        // Disabled is insufficient: removed integrations must not contribute SDKs or manifest entries.
        listOf(
            "org.android.agoo.xiaomi.MiPushBroadcastReceiver",
            "com.xiaomi.push.service.receivers.PingReceiver",
            "com.xiaomi.mipush.sdk.MessageHandleService",
            "com.xiaomi.mipush.sdk.PushMessageHandler",
            "com.xiaomi.push.service.XMPushService",
            "com.xiaomi.push.service.XMJobService",
            "com.xiaomi.push.service.UploadLogSDKService",
            "com.xiaomi.push.service.logupload.LogUploadFileProvider",
            "com.huawei.hms.aaid.InitProvider",
            "com.huawei.agconnect.core.provider.AGConnectInitializeProvider",
            "com.huawei.hms.support.api.push.PushProvider",
            "org.android.agoo.huawei.HuaweiRcvService",
            "com.huawei.hms.support.api.push.service.HmsMsgService",
            "com.huawei.hms.support.api.push.PushMsgReceiver",
            "com.huawei.hms.support.api.push.PushReceiver",
        ).forEach(::assertMissingComponent)
        listOf(
            "com.xiaomi.mipush.sdk.MiPushClient",
            "org.android.agoo.xiaomi.MiPushRegistar",
            "com.huawei.hms.push.HmsMessaging",
            "com.huawei.agconnect.AGConnectInstance",
            "org.android.agoo.huawei.HuaWeiRegister",
        ).forEach { name ->
            val result = runCatching { Class.forName(name, false, app.classLoader) }
            assertTrue("$name must be absent, rather than merely disabled", result.exceptionOrNull() is ClassNotFoundException)
        }
        // The two supported transports must remain in the actual installed application.
        assertNotNull(Class.forName("com.google.firebase.messaging.FirebaseMessaging", false, app.classLoader))
        assertNotNull(Class.forName("com.umeng.message.PushAgent", false, app.classLoader))
    }

    @Test fun realNotificationDeliveryRejectsReplaysAndOlderRevisions() {
        grantNotifications()
        PushRuntime.initialize(app, PushConfiguration(consent = true))
        PushRuntime.onSessionAvailable(app)
        val id = "instrumented:${UUID.randomUUID()}"
        val event = NativePushEvent(id, 3, PushEventKind.ANNOUNCEMENT, "修订 3", "设备测试通知")
        assertTrue(notices.post(event))
        assertFalse(notices.post(event))
        assertFalse(notices.post(event.copy(revision = 2, title = "旧修订")))
        assertTrue(notices.post(event.copy(revision = 4, title = "修订 4")))
        val posted = awaitNotifications("The newer notification revision must replace the previous title") { active ->
            active.singleOrNull { it.tag == "event:$id" }?.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "修订 4"
        }.single { it.tag == "event:$id" }
        assertEquals("修订 4", posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Persistence matters: a newly constructed delivery facade must also reject the replay.
        assertFalse(LaundryNotifications(app).post(event.copy(revision = 4)))
    }

    @Test fun logoutCancelsScheduledWorkAndErasesPrivateWidgetAndLoginState() = runBlocking {
        grantNotifications()
        val origin = serviceEndpoint(BuildConfig.DEBUG, BuildConfig.API_BASE_URL,
            app.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("endpoint", null))
        SecureSession(app).save(JSONObject().put("origin", origin)
            .put("accessToken", "ma1.widget-logout-fixture.test-secret").put("user", JSONObject().put("id", "widget-logout-fixture")))
        PushRuntime.initialize(app, PushConfiguration(consent = true))
        PushRuntime.onSessionAvailable(app)
        val task = TaskProgress("instrumented-task", 1, "测试洗衣机", System.currentTimeMillis(), System.currentTimeMillis() + 600_000)
        LaundryReminders.schedule(app, task)
        assertTrue(notices.updateProgress(task))
        awaitNotifications("The progress notification must be visible before testing logout") { active ->
            active.count { it.tag == "progress:${task.taskId}" } == 1
        }
        LaundryWidgetStore.update(app, LaundryWidgetSnapshot("测试楼", 2, 3, 0, System.currentTimeMillis(), "测试洗衣机", task.estimatedFinishAtMillis))
        DingTalkLoginBridge.createRequest(app, "instrumented-installation")
        assertTrue(app.getSharedPreferences("laundry_widget_snapshot", Context.MODE_PRIVATE).contains("snapshot"))
        assertTrue(WorkManager.getInstance(app).getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).any { !it.state.isFinished })

        PushRuntime.logout(app)

        assertFalse(PushRuntime.isSessionActive(app))
        assertTrue(awaitNotifications("Logout must cancel all visible notifications") { it.isEmpty() }.isEmpty())
        assertTrue(app.getSharedPreferences("laundry_widget_snapshot", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(app.getSharedPreferences("native_login_pending", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(app.getSharedPreferences("laundry_reminder_state", Context.MODE_PRIVATE).all.isEmpty())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (WorkManager.getInstance(app).getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).any { !it.state.isFinished } && System.nanoTime() < deadline) {
            Thread.sleep(50) // Cancellation is asynchronous inside WorkManager's real database executor.
        }
        assertTrue(WorkManager.getInstance(app).getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).all { it.state.isFinished })
    }

    @Test fun serverAndLocalTaskEventsShareOneCardAndConfirmationReplacesTheEstimate() {
        grantNotifications()
        PushRuntime.initialize(app, PushConfiguration(consent = true))
        PushRuntime.onSessionAvailable(app)
        val taskId = "coalesced:${UUID.randomUUID()}"
        val finish = System.currentTimeMillis() + 60_000
        val local = NativePushEvent("local-$taskId", 7, PushEventKind.QUEUE_THREE_MINUTES, "预计即将结束", "本地估计", taskId, finishAtMillis = finish)
        assertTrue(notices.post(local))
        val remote = local.copy(eventId = "remote-$taskId", revision = 1)
        assertFalse(notices.post(remote))
        assertTrue(notices.wasHandled(remote))
        assertEquals(1, awaitNotifications("The two reminder transports must produce a single lead card") { active ->
            active.count { it.tag == "taskPhase:$taskId:lead" } == 1
        }.count { it.tag == "taskPhase:$taskId:lead" })
        val estimate = local.copy(eventId = "estimate-$taskId", kind = PushEventKind.ESTIMATED_END)
        assertTrue(notices.post(estimate))
        assertTrue(notices.post(estimate.copy(eventId = "confirmed-$taskId", revision = 1,
            kind = PushEventKind.TASK_FINISHED, serverConfirmed = true, title = "设备确认结束")))
        assertFalse(notices.post(estimate.copy(revision = 8)))
        val end = awaitNotifications("The device-confirmed title must replace the estimated title") { active ->
            active.singleOrNull { it.tag == "taskPhase:$taskId:end" }?.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "设备确认结束"
        }.single { it.tag == "taskPhase:$taskId:end" }
        assertEquals("设备确认结束", end.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(notices.post(local.copy(eventId = "expired-$taskId", finishAtMillis = System.currentTimeMillis() - 1)))
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

    @Test fun nativeNotificationNavigationHandlesColdAndWarmStartsAndRejectsUrls() {
        // ActivityScenario matches lifecycle callbacks by Intent.filterEquals. Keep the same
        // action for the real warm launches, otherwise it ignores RESUMED after setIntent().
        val intent = Intent(app, MainActivity::class.java).setAction("weshirt.open.navigation-test")
            .putExtra(NativeNavigation.DESTINATION_EXTRA, "CHAT_MENTION")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[AppViewModel::class.java]
                assertEquals(2, vm.state.value.tab)
                assertFalse(activity.intent.hasExtra(NativeNavigation.DESTINATION_EXTRA))
            }
            requireNotNull(NativeNavigation.pendingIntent(app, "MY_LAUNDRY", "navigation-test")).send()
            awaitActivity(scenario) { ViewModelProvider(it)[AppViewModel::class.java].state.value.tab == 1 }
            scenario.onActivity { activity ->
                assertEquals(1, ViewModelProvider(activity)[AppViewModel::class.java].state.value.tab)
                assertFalse(activity.intent.hasExtra(NativeNavigation.DESTINATION_EXTRA))
            }
            app.startActivity(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setAction("weshirt.open.navigation-test").putExtra("navigationTestSequence", 2)
                .putExtra(NativeNavigation.DESTINATION_EXTRA, "https://example.invalid/untrusted"))
            awaitActivity(scenario) { it.intent.getIntExtra("navigationTestSequence", 0) == 2 }
            scenario.onActivity { activity -> assertEquals(1, ViewModelProvider(activity)[AppViewModel::class.java].state.value.tab) }
            scenario.recreate()
            scenario.onActivity { activity -> assertEquals(1, ViewModelProvider(activity)[AppViewModel::class.java].state.value.tab) }
        }
    }

    @Test fun refreshingSameTaskPreservesPendingWorkInsteadOfPostponingIt() {
        grantNotifications()
        PushRuntime.initialize(app, PushConfiguration(consent = true))
        PushRuntime.onSessionAvailable(app)
        val task = TaskProgress("stable-reminder", 7, "测试机器", System.currentTimeMillis(), System.currentTimeMillis() + 600_000)
        LaundryReminders.schedule(app, task)
        val manager = WorkManager.getInstance(app)
        val before = manager.getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }.map { it.id }.toSet()
        assertEquals(2, before.size)
        LaundryReminders.schedule(app, task)
        val after = manager.getWorkInfosByTag("laundry_reminder").get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }.map { it.id }.toSet()
        assertEquals(before, after)
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        }
        assertTrue("The emulator must allow notification permission", notices.capabilities().permissionGranted)
    }

    /** NotificationManager enqueues notify/cancel work in the system process asynchronously. */
    private fun awaitNotifications(
        description: String,
        condition: (Array<StatusBarNotification>) -> Boolean,
    ): Array<StatusBarNotification> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var active = manager.activeNotifications
        while (!condition(active) && System.nanoTime() < deadline) {
            Thread.sleep(25)
            active = manager.activeNotifications
        }
        assertTrue(description, condition(active))
        return active
    }

    private fun awaitActivity(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var matched = false
        while (!matched && System.nanoTime() < deadline) {
            scenario.onActivity { matched = condition(it) }
            if (!matched) Thread.sleep(50)
        }
        assertTrue("The native activity must receive the launch intent", matched)
    }

    private fun assertMissingComponent(name: String) {
        val component = ComponentName(app.packageName, name)
        val info = runCatching { app.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        val provider = runCatching { app.packageManager.getProviderInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        val service = runCatching { app.packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        val receiver = runCatching { app.packageManager.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        assertNull(name, info)
        assertNull(name, provider)
        assertNull(name, service)
        assertNull(name, receiver)
    }
}
