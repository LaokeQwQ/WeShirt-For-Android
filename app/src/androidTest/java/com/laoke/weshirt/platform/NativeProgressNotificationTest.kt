package com.laoke.weshirt.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.NotificationCapabilities
import com.laoke.weshirt.platform.notification.TaskProgress
import com.laoke.weshirt.platform.push.PushConfiguration
import com.laoke.weshirt.platform.push.PushRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Validates the notification actually accepted by Android, not an app-side capability flag. */
@RunWith(AndroidJUnit4::class)
class NativeProgressNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val notices get() = LaundryNotifications(app)

    @Before fun prepare() {
        runBlocking { PushRuntime.logout(app) }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        PushRuntime.initialize(app, PushConfiguration(consent = true))
        PushRuntime.onSessionAvailable(app)
        assertTrue(notices.capabilities().permissionGranted)
    }

    @After fun cleanup() { runBlocking { PushRuntime.logout(app) } }

    @Test fun postedProgressContainsMachineStateEtaTimerAndRealPlatformProgressTemplate() {
        val now = System.currentTimeMillis()
        val task = TaskProgress("progress:${UUID.randomUUID()}", 1, "一层 · 2号机", now - 20 * 60_000,
            now + 20 * 60_000, userRequestedTracking = true, kind = "dryer")
        assertTrue(notices.updateProgress(task))
        await("The platform must receive the task's progress notification") { posted(task) != null }
        val notification = requireNotNull(posted(task))
        assertEquals("一层 · 2号机 · 烘干中", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("预计"))
        assertEquals(listOf("隐藏进度"), notification.actions.map { it.title.toString() })
        assertNotNull(notification.contentIntent)
        assertEquals(task.estimatedFinishAtMillis, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(task.machineName))
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.Builder.recoverBuilder(app, notification).style as Notification.ProgressStyle
            assertEquals(1000, style.progressMax)
            assertTrue(style.progress in 499..502)
            assertFalse(style.isProgressIndeterminate)
            assertNotNull(style.progressTrackerIcon)
            // Null is intentional: any non-null short text masks the system's ticking chip timer.
            assertNull(notification.shortCriticalText)
            val candidate = notices.buildProgressNotification(task, NotificationCapabilities(true, true, true), now)
            assertTrue("Android's own eligibility validator must accept our promoted candidate", candidate.hasPromotableCharacteristics())
            val denied = notices.buildProgressNotification(task, NotificationCapabilities(true, true, false), now)
            assertFalse("An OS-level opt out must leave a standard notification", denied.hasPromotableCharacteristics())
        } else {
            assertEquals(1000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertTrue(notification.extras.getInt(Notification.EXTRA_PROGRESS) in 499..502)
        }
        assertTrue(notices.updateProgress(task.copy(revision = 2, estimatedFinishAtMillis = now - 1)))
        await("An elapsed estimate must stop the live timer without claiming completion") {
            posted(task)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "预计时间已到，设备尚未确认完成"
        }
        val elapsed = requireNotNull(posted(task))
        assertFalse(elapsed.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(elapsed.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(0, elapsed.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        if (Build.VERSION.SDK_INT >= 36) assertFalse(elapsed.hasPromotableCharacteristics())
    }

    @Test fun unknownStartDoesNotFakePercentageAndHideActionIsNotUndoneByTheNextRefresh() {
        val now = System.currentTimeMillis()
        val task = TaskProgress("queue:${UUID.randomUUID()}", 1, "预约机器", 0,
            now + 600_000, userRequestedTracking = true)
        assertTrue(notices.updateProgress(task))
        await("The queue progress notification must be delivered") { posted(task) != null }
        val notification = requireNotNull(posted(task))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().endsWith("等候中"))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        notification.actions.single { it.title.toString() == "隐藏进度" }.actionIntent.send()
        await("The native hide action must cancel the notification") { posted(task) == null }
        assertFalse("Refresh must respect the user's dismissal", notices.updateProgress(task.copy(revision = 2)))
        assertNull(posted(task))
    }

    private fun posted(task: TaskProgress) = manager.activeNotifications
        .firstOrNull { it.tag == "progress:${task.taskId}" }?.notification

    private fun await(message: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < end) Thread.sleep(25)
        assertTrue(message, predicate())
    }
}
