package com.laoke.weshirt.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.LaundryReminderWorker
import com.laoke.weshirt.platform.notification.LaundryReminders
import com.laoke.weshirt.platform.notification.TaskProgress
import com.laoke.weshirt.platform.push.NativePushEvent
import com.laoke.weshirt.platform.push.PushConfiguration
import com.laoke.weshirt.platform.push.PushEventKind
import com.laoke.weshirt.platform.push.PushRuntime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercise the real WorkManager database, stale worker input and notification receipts. */
@RunWith(AndroidJUnit4::class)
class ReminderLeadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val manager get() = WorkManager.getInstance(app)
    private val prefs get() = app.getSharedPreferences("laundry_reminder_state", Context.MODE_PRIVATE)

    @Before fun prepare() {
        runBlocking { PushRuntime.logout(app) }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        PushRuntime.initialize(app, PushConfiguration(consent = true))
        PushRuntime.onSessionAvailable(app)
        assertTrue(LaundryNotifications(app).capabilities().permissionGranted)
    }

    @After fun cleanup() { runBlocking { PushRuntime.logout(app) } }

    @Test fun changingLeadReplacesOnlyTheAdvanceWorkAndRefreshDoesNotPostponeIt() {
        val task = task("reschedule")
        LaundryReminders.schedule(app, task)
        val originalLead = pending(task.taskId, "soon")
        val originalEnd = pending(task.taskId, "estimated")
        assertTrue(originalLead.initialDelayMillis in 16 * 60_000L..18 * 60_000L)

        LaundryReminders.schedule(app, task.copy(leadMinutes = 10))
        val longerLead = awaitWork(task.taskId, "soon") { works -> works.singleOrNull { !it.state.isFinished }?.let { it.id != originalLead.id } == true }.single { !it.state.isFinished }
        assertNotEquals(originalLead.id, longerLead.id)
        assertTrue(longerLead.initialDelayMillis in 9 * 60_000L..11 * 60_000L)
        assertEquals(originalEnd.id, pending(task.taskId, "estimated").id)

        LaundryReminders.schedule(app, task.copy(leadMinutes = 10))
        assertEquals(longerLead.id, pending(task.taskId, "soon").id)
        assertEquals(originalEnd.id, pending(task.taskId, "estimated").id)
        assertEquals(10, JSONObject(prefs.getString(task.taskId, "{}")!!).getInt("leadMinutes"))
    }

    @Test fun disablingAdvanceReminderCancelsOnlyAdvanceWork() {
        val task = task("disabled")
        LaundryReminders.schedule(app, task)
        pending(task.taskId, "soon")
        val end = pending(task.taskId, "estimated")
        LaundryReminders.schedule(app, task.copy(leadMinutes = 0))
        assertTrue(awaitWork(task.taskId, "soon") { it.all { work -> work.state.isFinished } }.all { it.state.isFinished })
        assertEquals(end.id, pending(task.taskId, "estimated").id)
        assertEquals(0, JSONObject(prefs.getString(task.taskId, "{}")!!).getInt("leadMinutes"))
    }

    @Test fun alreadyDequeuedWorkerCannotSendUsingDisabledOrChangedLeadOrFinish() {
        val finish = System.currentTimeMillis() + 120_000L
        // These workers run inside their old warning window. Without the current-policy check,
        // they would really post a notification; no pending scheduler races this assertion.
        listOf(0, 5).forEach { currentLead ->
            val id = "stale-lead-$currentLead:${UUID.randomUUID()}"
            store(id, finish, currentLead)
            runOldWorker(id, finish, 3)
            assertNoLeadDelivered(id, finish)
        }
        val id = "stale-finish:${UUID.randomUUID()}"
        store(id, finish, 3)
        runOldWorker(id, finish + 60_000L, 3)
        assertNoLeadDelivered(id, finish)
    }

    @Test fun changingLeadDoesNotResendAPhaseAlreadyDeliveredByServer() {
        val task = task("deduplicated")
        LaundryReminders.schedule(app, task)
        pending(task.taskId, "soon")
        val end = pending(task.taskId, "estimated")
        LaundryReminders.receivedRemotePhase(app, NativePushEvent("server:${task.taskId}", 1, PushEventKind.QUEUE_THREE_MINUTES,
            "测试提前提醒", "已由服务器送达", task.taskId, finishAtMillis = task.estimatedFinishAtMillis))
        LaundryReminders.schedule(app, task.copy(leadMinutes = 10))
        assertTrue(awaitWork(task.taskId, "soon") { it.all { work -> work.state.isFinished } }.all { it.state.isFinished })
        assertEquals(end.id, pending(task.taskId, "estimated").id)
        assertEquals(task.revision, JSONObject(prefs.getString(task.taskId, "{}")!!).getLong("soonDeliveredRevision"))
    }

    private fun task(prefix: String) = TaskProgress("$prefix:${UUID.randomUUID()}", 7, "提醒设置测试机器", System.currentTimeMillis(), System.currentTimeMillis() + 20 * 60_000L)

    private fun store(id: String, finish: Long, lead: Int) = synchronized(LaundryReminders) {
        prefs.edit().putString(id, JSONObject().put("revision", 7).put("finish", finish).put("leadMinutes", lead).put("name", "提醒设置测试机器").toString()).commit()
    }

    private fun runOldWorker(id: String, finish: Long, lead: Int) {
        val request = OneTimeWorkRequestBuilder<LaundryReminderWorker>().setInputData(Data.Builder()
            .putString("taskId", id).putLong("revision", 7).putString("kind", "soon").putLong("finish", finish).putInt("leadMinutes", lead).build())
            .addTag("laundry_reminder").build()
        manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var work = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)
        while (work?.state?.isFinished != true && System.nanoTime() < deadline) {
            Thread.sleep(25)
            work = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, work?.state)
    }

    private fun assertNoLeadDelivered(id: String, finish: Long) {
        assertFalse(JSONObject(prefs.getString(id, "{}")!!).has("soonDeliveredRevision"))
        assertFalse(LaundryNotifications(app).wasHandled(NativePushEvent("task:$id:soon", 7,
            PushEventKind.QUEUE_THREE_MINUTES, "测试", "测试", id, finishAtMillis = finish)))
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag == "taskPhase:$id:lead" })
    }

    private fun pending(id: String, phase: String) = awaitWork(id, phase) { it.count { work -> !work.state.isFinished } == 1 }.single { !it.state.isFinished }

    private fun awaitWork(id: String, phase: String, condition: (List<WorkInfo>) -> Boolean): List<WorkInfo> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var works = manager.getWorkInfosForUniqueWork("laundry_reminder:$id:$phase").get(10, TimeUnit.SECONDS)
        while (!condition(works) && System.nanoTime() < deadline) {
            Thread.sleep(25)
            works = manager.getWorkInfosForUniqueWork("laundry_reminder:$id:$phase").get(10, TimeUnit.SECONDS)
        }
        assertTrue("Expected reminder work state was not reached", condition(works))
        return works
    }
}
