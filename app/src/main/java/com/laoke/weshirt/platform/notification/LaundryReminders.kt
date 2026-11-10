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
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** WorkManager is inexact under Doze. Server pushes provide authoritative completion. */
object LaundryReminders {
    internal const val PREFS = "laundry_reminder_state"
    private const val CONFIRMED_PREFS = "laundry_confirmed_cycles"
    private const val TAG = "laundry_reminder"

    fun schedule(context: Context, task: TaskProgress) {
        if (!task.active || !PushRuntime.notificationsAllowed(context) || isConfirmedFinished(context, task.taskId)) { cancel(context, task.taskId); return }
        if (task.taskId.isBlank() || task.taskId.length > 160 || task.revision < 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = runCatching { JSONObject(prefs.getString(task.taskId, "{}")!!) }.getOrNull()
        if (previous != null && previous.optLong("revision", -1) > task.revision) return
        val unchanged = previous != null && previous.optLong("revision", -1) == task.revision && previous.optLong("finish", -1) == task.estimatedFinishAtMillis
        prefs.edit().putString(task.taskId, (previous ?: JSONObject()).put("revision", task.revision)
            .put("finish", task.estimatedFinishAtMillis).put("name", task.machineName.take(100)).toString()).commit()
        val manager = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()
        listOf("soon" to task.estimatedFinishAtMillis - 180_000L, "estimated" to task.estimatedFinishAtMillis).forEach { (kind, at) ->
            // Do not cancel a pending three-minute warning every time the foreground board refreshes.
            if (kind == "soon" && task.estimatedFinishAtMillis <= now) { manager.cancelUniqueWork(name(task.taskId, kind)); return@forEach }
            if (previous?.optLong("${kind}DeliveredRevision", -1) == task.revision) return@forEach
            if (at < now - 15 * 60_000L) return@forEach
            val request = OneTimeWorkRequestBuilder<LaundryReminderWorker>()
                .setInputData(Data.Builder().putString("taskId", task.taskId).putLong("revision", task.revision)
                    .putString("kind", kind).build())
                .setInitialDelay((at - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag(TAG).build()
            manager.enqueueUniqueWork(name(task.taskId, kind), if (unchanged) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun confirmFinished(context: Context, taskId: String) {
        if (taskId.isBlank() || taskId.length > 160) return
        val prefs = context.getSharedPreferences(CONFIRMED_PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().putLong(taskId, System.currentTimeMillis())
        prefs.all.entries.sortedBy { (it.value as? Long) ?: 0L }.take((prefs.all.size - 255).coerceAtLeast(0)).forEach { edit.remove(it.key) }
        edit.commit()
        cancel(context, taskId)
        LaundryNotifications(context).cancelProgress(taskId)
    }
    fun isConfirmedFinished(context: Context, taskId: String): Boolean = context.getSharedPreferences(CONFIRMED_PREFS, Context.MODE_PRIVATE).contains(taskId)
    fun cancel(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(taskId).commit()
        WorkManager.getInstance(context).cancelUniqueWork(name(taskId, "soon"))
        WorkManager.getInstance(context).cancelUniqueWork(name(taskId, "estimated"))
    }
    fun cancelAll(context: Context, clearConfirmed: Boolean = false) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        if (clearConfirmed) context.getSharedPreferences(CONFIRMED_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun name(id: String, kind: String) = "$TAG:$id:$kind"
}

class LaundryReminderWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!PushRuntime.notificationsAllowed(applicationContext)) return Result.success()
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val revision = inputData.getLong("revision", -1L)
        val kind = inputData.getString("kind") ?: return Result.failure()
        val raw = applicationContext.getSharedPreferences(LaundryReminders.PREFS, Context.MODE_PRIVATE).getString(taskId, null)
            ?: return Result.success()
        val state = runCatching { JSONObject(raw) }.getOrNull() ?: return Result.failure()
        if (revision != state.optLong("revision", -2)) return Result.success()
        val finish = state.optLong("finish", 0)
        val now = System.currentTimeMillis()
        if (now > finish + 15 * 60_000L || (kind == "soon" && now >= finish)) return Result.success()
        val machine = state.optString("name", "洗衣机")
        val remaining = ((finish - now + 59_999L) / 60_000L).coerceAtLeast(1)
        val event = if (kind == "soon") NativePushEvent("task:$taskId:soon", revision,
            PushEventKind.QUEUE_THREE_MINUTES, "洗衣预计还有 $remaining 分钟", "$machine 即将到达预计结束时间，可准备前往。设备实际状态以看板为准。", taskId)
        else NativePushEvent("task:$taskId:estimated", revision, PushEventKind.ESTIMATED_END,
            "洗衣预计时间已到", "$machine 已到预计结束时间，尚待设备确认结束，请查看最新状态。", taskId)
        if (kind == "estimated") {
            LaundryNotifications(applicationContext).updateProgress(
                TaskProgress(taskId, revision, machine, 0L, finish, userRequestedTracking = true))
            LaundryWidgetStore.refresh(applicationContext)
        }
        if (LaundryNotifications(applicationContext).post(event)) {
            val prefs = applicationContext.getSharedPreferences(LaundryReminders.PREFS, Context.MODE_PRIVATE)
            val latest = runCatching { JSONObject(prefs.getString(taskId, "{}")!!) }.getOrNull()
            if (latest != null && latest.optLong("revision", -1) == revision)
                prefs.edit().putString(taskId, latest.put("${kind}DeliveredRevision", revision).toString()).commit()
        }
        return Result.success()
    }
}
