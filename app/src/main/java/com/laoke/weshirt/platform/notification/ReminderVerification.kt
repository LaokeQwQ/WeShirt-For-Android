package com.laoke.weshirt.platform.notification

import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.epoch
import com.laoke.weshirt.data.objects
import com.laoke.weshirt.data.str

internal data class VerifiedReminder(
    val finish: Long?,
    val name: String,
    val active: Boolean = true,
    val confirmed: Boolean = false,
    val stale: Boolean = false,
    val fallback: Boolean = false,
    val leadMinutes: Int? = null,
)

internal enum class ReminderAction { CANCEL, RESCHEDULE, WAIT, SOON, FINISHED, ESTIMATE }

/** A zero countdown is never hardware completion. Only failed refreshes may
 * produce an explicitly estimated local fallback. */
internal fun reminderAction(current: VerifiedReminder, scheduledFinish: Long, kind: String, lead: Int, now: Long): ReminderAction {
    if (!current.active) return ReminderAction.CANCEL
    if (current.confirmed && !current.stale && !current.fallback) return ReminderAction.FINISHED
    val finish = current.finish ?: return ReminderAction.WAIT
    if (!current.fallback && current.stale) return ReminderAction.WAIT
    if (kotlin.math.abs(finish - scheduledFinish) > 1_000L) return ReminderAction.RESCHEDULE
    if (now > finish + 15 * 60_000L) return ReminderAction.CANCEL
    if (kind == "soon") return if (lead > 0 && now < finish && now >= finish - lead * 60_000L) ReminderAction.SOON else ReminderAction.WAIT
    return if (current.fallback && now >= finish) ReminderAction.ESTIMATE else ReminderAction.WAIT
}

internal suspend fun fetchVerifiedReminder(api:ApiClient,taskId:String,now:()->Long=System::currentTimeMillis):VerifiedReminder {
    val user=api.call("/api/auth/me").optJSONObject("user") ?: return VerifiedReminder(null,"",active=false)
    if(!user.optBoolean("notifyOn",true)||user.optBoolean("disabled")||user.optBoolean("blocked"))return VerifiedReminder(null,"",active=false)
    val queued=taskId.startsWith("queue:")
    val access=api.call("/api/user/permissions").optJSONObject("permissions")?.optJSONObject(if(queued)"queue" else "tasks")
        ?: return VerifiedReminder(null,"",active=false)
    if(!access.optBoolean("view"))return VerifiedReminder(null,"",active=false)
    if(!queued){
        val response=api.call("/api/tasks")
        val row=response.optJSONArray("tasks")?.objects()?.find { it.str("id")==taskId } ?: return VerifiedReminder(null,"",active=false)
        val offset=epoch(response.str("serverNow"))?.minus(now()) ?: 0L
        return VerifiedReminder(epoch(row.str("endAt"))?.minus(offset),row.str("machineName","洗衣机"),
            active=!row.optBoolean("done")&&row.str("takenAt").isBlank()&&!row.optBoolean("archived"),
            confirmed=row.optBoolean("completionConfirmed"),stale=row.optBoolean("timeStale"),
            fallback=row.optBoolean("timeStale")&&row.str("timingSource")=="user_report",leadMinutes=user.optInt("notifyLeadMin",0))
    }
    val dorm=user.str("dormBuilding")
    if(dorm.isBlank())return VerifiedReminder(null,"",active=false)
    val queue=api.call("/api/queue?dorm=${api.encoded(dorm)}").optJSONArray("queue")?.objects()?.find { it.str("id")==taskId.removePrefix("queue:")&&it.optBoolean("mine")&&it.optInt("position")==1 }
        ?: return VerifiedReminder(null,"",active=false)
    val response=api.call("/api/machines?dorm=${api.encoded(dorm)}",auth=false)
    val board=response.optJSONObject("board") ?: return VerifiedReminder(null,"",stale=true)
    val machine=board.optJSONArray("floors")?.objects()?.flatMap { it.optJSONArray("machines")?.objects().orEmpty() }?.find { it.str("id")==queue.str("machineId") }
        ?: return VerifiedReminder(null,"",active=false)
    val offset=epoch(response.optJSONObject("freshness")?.str("serverNow").orEmpty())?.minus(now()) ?: 0L
    val stale=response.optJSONObject("freshness")?.optBoolean("stale")==true || machine.optJSONObject("health")?.optBoolean("stale")==true
    return VerifiedReminder(epoch(machine.str("finishAt"))?.minus(offset),machine.str("displayName",machine.str("name","预约机器")),
        confirmed=!stale&&machine.str("state")=="idle"&&machine.str("upstreamState","idle")=="idle"&&!machine.optBoolean("registrationPending"),stale=stale)
}
