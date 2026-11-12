package com.laoke.weshirt.platform.notification

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.ApiException
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.serviceEndpoint
import com.laoke.weshirt.data.str
import com.laoke.weshirt.platform.push.NativePushEvent
import com.laoke.weshirt.platform.push.PushEventKind
import com.laoke.weshirt.platform.push.PushRuntime
import com.laoke.weshirt.platform.push.TaskNoticeCoalescing
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
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

class LaundryReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    /** Network validation happens outside the reminder lock. A slow connection
     * cannot block logout, changing reminder preferences or replacing a task. */
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val revision = inputData.getLong("revision", -1L)
        val kind = inputData.getString("kind") ?: return Result.failure()
        if (kind != "soon" && kind != "estimated") return Result.failure()
        val session = SecureSession(applicationContext)
        val before = session.read() ?: return Result.success()
        val account = accountKey(before)
        val state = synchronized(LaundryReminders) { currentState(taskId, revision, kind) } ?: return Result.success()
        val origin = serviceEndpoint(BuildConfig.DEBUG, BuildConfig.API_BASE_URL,
            applicationContext.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("endpoint", null)).trimEnd('/')
        if (origin.isBlank() || before.str("origin") != origin) return Result.success()
        val transport = OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS)
            .callTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        val api = ApiClient(session,applicationContext.cacheDir,transport).also { it.baseUrl = origin }
        val finish = state.optLong("finish")
        val verified = try { fetchVerifiedReminder(api,taskId) }
        catch (error: CancellationException) { throw error }
        catch (error: ApiException) {
            if (error.status in 400..499 && error.status !in setOf(408,429)) return Result.success()
            VerifiedReminder(finish,state.str("name","洗衣机"),fallback=true)
        }
        catch (_: Exception) { return Result.retry() }

        synchronized(LaundryReminders) {
        if (accountKey(session.read()) != account || currentState(taskId,revision,kind) == null) return Result.success()
        val leadMinutes = reminderLeadMinutes(state.optInt("leadMinutes",3))
        val latestLead=verified.leadMinutes?.let(::reminderLeadMinutes) ?: leadMinutes
        if(latestLead!=leadMinutes){
            LaundryReminders.schedule(applicationContext,TaskProgress(taskId,revision,verified.name,0L,verified.finish?:finish,userRequestedTracking=true,leadMinutes=latestLead))
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val action = reminderAction(verified,finish,kind,leadMinutes,now)
        when(action) {
            ReminderAction.CANCEL -> { LaundryReminders.cancel(applicationContext,taskId); return Result.success() }
            ReminderAction.RESCHEDULE -> {
                val next = TaskProgress(taskId,revision+1,verified.name,0L,verified.finish ?: return Result.retry(),userRequestedTracking=true,leadMinutes=leadMinutes)
                LaundryReminders.schedule(applicationContext,next)
                LaundryNotifications(applicationContext).updateProgress(next)
                return Result.success()
            }
            ReminderAction.WAIT -> return Result.retry()
            else -> Unit
        }
        val currentFinish = verified.finish ?: finish
        val machine = verified.name
        val remaining = ((currentFinish - now + 59_999L) / 60_000L).coerceAtLeast(1)
        val event = when(action) {
            ReminderAction.SOON -> NativePushEvent("task:$taskId:soon",revision,PushEventKind.QUEUE_THREE_MINUTES,
                "洗衣预计还有 $remaining 分钟",if(verified.fallback) "$machine · 网络暂不可用，按上次计时预计，请现场确认。" else "$machine · 按最新平台时间提醒，请现场确认衣物情况。",taskId,finishAtMillis=currentFinish)
            ReminderAction.FINISHED -> NativePushEvent("task:$taskId:finished",revision,PushEventKind.TASK_FINISHED,
                "洗衣已结束", "$machine · 平台已确认结束，请现场确认并取衣。",taskId,serverConfirmed=true,finishAtMillis=currentFinish)
            else -> NativePushEvent("task:$taskId:estimated",revision,PushEventKind.ESTIMATED_END,
                "洗衣预计时间已到", "$machine · 网络暂不可用，上次计时已到，尚未确认机器结束。",taskId,finishAtMillis=currentFinish)
        }
        if (action == ReminderAction.ESTIMATE) {
            LaundryNotifications(applicationContext).updateProgress(
                TaskProgress(taskId, revision, machine, 0L, currentFinish, userRequestedTracking = true))
            LaundryWidgetStore.refresh(applicationContext)
        }
        val notices = LaundryNotifications(applicationContext)
        if (notices.post(event) || notices.wasHandled(event)) {
            if(action == ReminderAction.FINISHED) { LaundryReminders.confirmFinished(applicationContext,taskId);return Result.success() }
            val prefs = applicationContext.getSharedPreferences(LaundryReminders.PREFS, Context.MODE_PRIVATE)
            val latest = runCatching { JSONObject(prefs.getString(taskId, "{}")!!) }.getOrNull()
            if (latest != null && latest.optLong("revision", -1) == revision)
                prefs.edit().putString(taskId, latest.put("${kind}DeliveredRevision", revision).put("${kind}DeliveredFinish", currentFinish).toString()).commit()
        }
        return Result.success()
        }
    }

    private fun currentState(taskId: String,revision:Long,kind:String): JSONObject? {
        if(!PushRuntime.notificationsAllowed(applicationContext) || LaundryReminders.isConfirmedFinished(applicationContext,taskId)) return null
        val raw=applicationContext.getSharedPreferences(LaundryReminders.PREFS,Context.MODE_PRIVATE).getString(taskId,null) ?: return null
        val state=runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val finish=state.optLong("finish",0)
        if(revision!=state.optLong("revision",-2)||inputData.getLong("finish",finish)!=finish)return null
        val lead=reminderLeadMinutes(state.optInt("leadMinutes",3))
        if(kind=="soon"&&(lead==0||inputData.getInt("leadMinutes",3)!=lead))return null
        if(state.optLong("${kind}DeliveredRevision",-1)==revision || state.optLong("${kind}DeliveredFinish",0).takeIf { it>0 }?.let { TaskNoticeCoalescing.sameFinish(it,finish) }==true)return null
        return state
    }

    private fun accountKey(value:JSONObject?):String? {
        value ?: return null
        val token=value.str("accessToken").split('.')
        val identity=if(token.size==3&&token[0]=="ma1")token[1] else value.optJSONObject("user")?.str("id") ?: value.str("refreshToken")
        return value.str("origin")+"|"+identity
    }
}
