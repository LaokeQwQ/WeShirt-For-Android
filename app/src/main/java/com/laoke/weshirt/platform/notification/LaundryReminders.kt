package com.laoke.weshirt.platform.notification

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.laoke.weshirt.platform.push.NativePushEvent
import com.laoke.weshirt.platform.push.PushEventKind
import com.laoke.weshirt.platform.push.PushRuntime
import com.laoke.weshirt.platform.push.TaskNoticeCoalescing
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal fun reminderLeadMinutes(value: Int): Int = value.takeIf { it == 0 || it == 3 || it == 5 || it == 10 } ?: 0

/** WorkManager is inexact under Doze. Server pushes provide authoritative completion. */
object LaundryReminders {
    internal const val PREFS = "laundry_reminder_state"
    private const val CONFIRMED_PREFS = "laundry_confirmed_cycles"
    private const val TAG = "laundry_reminder"

    @Synchronized fun schedule(context: Context, task: TaskProgress) {
        if (!task.active || !PushRuntime.notificationsAllowed(context) || isConfirmedFinished(context, task.taskId)) { cancel(context, task.taskId); return }
        if (task.taskId.isBlank() || task.taskId.length > 160 || task.revision < 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = runCatching { JSONObject(prefs.getString(task.taskId, "{}")!!) }.getOrNull()
        if (previous != null && previous.optLong("revision", -1) > task.revision) return
        val unchanged = previous != null && previous.optLong("revision", -1) == task.revision && previous.optLong("finish", -1) == task.estimatedFinishAtMillis
        val leadMinutes = reminderLeadMinutes(task.leadMinutes)
        val unchangedLead = unchanged && previous?.optInt("leadMinutes", 3) == leadMinutes
        prefs.edit().putString(task.taskId, (previous ?: JSONObject()).put("revision", task.revision)
            .put("finish", task.estimatedFinishAtMillis).put("name", task.machineName.take(100)).put("leadMinutes", leadMinutes).toString()).commit()
        val manager = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()
        listOf("soon" to task.estimatedFinishAtMillis - leadMinutes * 60_000L, "estimated" to task.estimatedFinishAtMillis).forEach { (kind, at) ->
            // Ordinary refreshes retain the pending work; changing the advance interval replaces it.
            if (kind == "soon" && (leadMinutes == 0 || task.estimatedFinishAtMillis <= now)) { manager.cancelUniqueWork(name(task.taskId, kind)); return@forEach }
            if (previous?.optLong("${kind}DeliveredRevision", -1) == task.revision) { manager.cancelUniqueWork(name(task.taskId, kind)); return@forEach }
            if (previous?.optLong("${kind}DeliveredFinish", 0)?.takeIf { it > 0 }?.let {
                    TaskNoticeCoalescing.sameFinish(it, task.estimatedFinishAtMillis) } == true) { manager.cancelUniqueWork(name(task.taskId, kind)); return@forEach }
            if (at < now - 15 * 60_000L) return@forEach
            val request = OneTimeWorkRequestBuilder<LaundryReminderWorker>()
                .setInputData(Data.Builder().putString("taskId", task.taskId).putLong("revision", task.revision)
                    .putLong("finish", task.estimatedFinishAtMillis).putInt("leadMinutes", leadMinutes).putString("kind", kind).build())
                .setInitialDelay((at - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag(TAG).build()
            val keep = if (kind == "soon") unchangedLead else unchanged
            manager.enqueueUniqueWork(name(task.taskId, kind), if (keep) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }
    }

    @Synchronized fun confirmFinished(context: Context, taskId: String) {
        if (taskId.isBlank() || taskId.length > 160) return
        val prefs = context.getSharedPreferences(CONFIRMED_PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().putLong(taskId, System.currentTimeMillis())
        prefs.all.entries.sortedBy { (it.value as? Long) ?: 0L }.take((prefs.all.size - 255).coerceAtLeast(0)).forEach { edit.remove(it.key) }
        edit.commit()
        cancel(context, taskId)
        LaundryNotifications(context).cancelProgress(taskId)
    }
    fun isConfirmedFinished(context: Context, taskId: String): Boolean = context.getSharedPreferences(CONFIRMED_PREFS, Context.MODE_PRIVATE).contains(taskId)
    @Synchronized fun receivedRemotePhase(context: Context, event: NativePushEvent) {
        val taskId = event.taskId ?: return
        val kind = when (event.kind) {
            PushEventKind.QUEUE_THREE_MINUTES -> "soon"
            PushEventKind.ESTIMATED_END -> "estimated"
            else -> return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = runCatching { JSONObject(prefs.getString(taskId, null) ?: return) }.getOrNull() ?: return
        val finish = state.optLong("finish", 0)
        if (!TaskNoticeCoalescing.sameFinish(finish, event.finishAtMillis)) return
        prefs.edit().putString(taskId, state.put("${kind}DeliveredRevision", state.optLong("revision", -1))
            .put("${kind}DeliveredFinish", finish).toString()).commit()
        WorkManager.getInstance(context).cancelUniqueWork(name(taskId, kind))
    }
    @Synchronized fun cancel(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(taskId).commit()
        WorkManager.getInstance(context).cancelUniqueWork(name(taskId, "soon"))
        WorkManager.getInstance(context).cancelUniqueWork(name(taskId, "estimated"))
    }
    @Synchronized fun cancelAll(context: Context, clearConfirmed: Boolean = false) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        if (clearConfirmed) context.getSharedPreferences(CONFIRMED_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun name(id: String, kind: String) = "$TAG:$id:$kind"
}

class LaundryReminderWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    // Coordinate the final settings check and delivery with schedule/cancel, including a lead of zero.
    override fun doWork(): Result {
        synchronized(LaundryReminders) {
        if (!PushRuntime.notificationsAllowed(applicationContext)) return Result.success()
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val revision = inputData.getLong("revision", -1L)
        val kind = inputData.getString("kind") ?: return Result.failure()
        if (kind != "soon" && kind != "estimated") return Result.failure()
        val raw = applicationContext.getSharedPreferences(LaundryReminders.PREFS, Context.MODE_PRIVATE).getString(taskId, null)
            ?: return Result.success()
        val state = runCatching { JSONObject(raw) }.getOrNull() ?: return Result.failure()
        if (revision != state.optLong("revision", -2)) return Result.success()
        val finish = state.optLong("finish", 0)
        if (inputData.getLong("finish", finish) != finish) return Result.success()
        val leadMinutes = reminderLeadMinutes(state.optInt("leadMinutes", 3))
        if (kind == "soon" && (leadMinutes == 0 || inputData.getInt("leadMinutes", 3) != leadMinutes)) return Result.success()
        if (state.optLong("${kind}DeliveredRevision", -1) == revision || state.optLong("${kind}DeliveredFinish", 0).takeIf { it > 0 }?.let { TaskNoticeCoalescing.sameFinish(it, finish) } == true) return Result.success()
        val now = System.currentTimeMillis()
        if (now > finish + 15 * 60_000L || (kind == "soon" && (now >= finish || now < finish - leadMinutes * 60_000L))) return Result.success()
        val machine = state.optString("name", "洗衣机")
        val remaining = ((finish - now + 59_999L) / 60_000L).coerceAtLeast(1)
        val event = if (kind == "soon") NativePushEvent("task:$taskId:soon", revision,
            PushEventKind.QUEUE_THREE_MINUTES, "洗衣预计还有 $remaining 分钟", "$machine 即将到达预计结束时间，可准备前往。设备实际状态以看板为准。", taskId, finishAtMillis = finish)
        else NativePushEvent("task:$taskId:estimated", revision, PushEventKind.ESTIMATED_END,
            "洗衣预计时间已到", "$machine 已到预计结束时间，尚待设备确认结束，请查看最新状态。", taskId, finishAtMillis = finish)
        if (kind == "estimated") {
            LaundryNotifications(applicationContext).updateProgress(
                TaskProgress(taskId, revision, machine, 0L, finish, userRequestedTracking = true))
            LaundryWidgetStore.refresh(applicationContext)
        }
        val notices = LaundryNotifications(applicationContext)
        if (notices.post(event) || notices.wasHandled(event)) {
            val prefs = applicationContext.getSharedPreferences(LaundryReminders.PREFS, Context.MODE_PRIVATE)
            val latest = runCatching { JSONObject(prefs.getString(taskId, "{}")!!) }.getOrNull()
            if (latest != null && latest.optLong("revision", -1) == revision)
                prefs.edit().putString(taskId, latest.put("${kind}DeliveredRevision", revision).put("${kind}DeliveredFinish", finish).toString()).commit()
        }
        return Result.success()
        }
    }
}
