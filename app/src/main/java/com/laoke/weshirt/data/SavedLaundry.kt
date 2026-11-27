package com.laoke.weshirt.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class SavedLaundry(val tasks: List<LaundryTask>, val syncedAt: Long)

internal const val SAVED_LAUNDRY_KEY = "sealed_laundry"

private const val MAX_SAVED_TASKS = 60
private const val MAX_SAVED_AGE = 14 * 24 * 60 * 60_000L

/** The stable native session identifier survives refresh-token rotation. */
internal fun savedLaundrySessionId(saved: JSONObject?): String? = saved?.str("accessToken")
    ?.split('.', limit = 3)?.takeIf { it.size == 3 && it[0] == "ma1" && it[1].isNotBlank() }?.get(1)

internal fun encodeSavedLaundry(origin: String, owner: String, sessionId: String, value: SavedLaundry): JSONObject = JSONObject()
    .put("origin", origin).put("owner", owner).put("session", sessionId).put("syncedAt", value.syncedAt)
    .put("tasks", JSONArray().apply {
        val (active, history) = laundryRecords(value.tasks, value.syncedAt)
        (active + history).take(MAX_SAVED_TASKS).forEach { task ->
            put(JSONObject().put("id", task.id).put("machineId", task.machineId).put("machineName", task.name.take(160))
                .put("kind", task.kind).put("done", task.done).put("estimated", task.estimated)
                .put("endAt", task.endAt?.let { Instant.ofEpochMilli(it).toString() })
                .put("startAt", task.startAt?.let { Instant.ofEpochMilli(it).toString() })
                .put("createdAt", task.createdAt?.let { Instant.ofEpochMilli(it).toString() })
                .put("expiresAt", task.expiresAt?.let { Instant.ofEpochMilli(it).toString() })
                .put("archived", task.archived).put("timingSource", task.timingSource)
                .put("completionConfirmed", task.completionConfirmed).put("clockOffsetMs", task.clockOffsetMs)
                .put("timeOffsetMinutes", task.timeOffsetMinutes).put("mode", task.mode)
                .put("program", task.program).put("minutes", task.minutes).put("takeState", task.takeState))
        }
    })

internal fun decodeSavedLaundry(data: JSONObject, saved: JSONObject?, origin: String, now: Long): SavedLaundry? {
    val owner = cachedSessionProfile(saved, origin)?.str("id") ?: return null
    val sessionId = savedLaundrySessionId(saved) ?: return null
    if (data.str("origin") != origin || data.str("owner") != owner || data.str("session") != sessionId) return null
    val syncedAt = data.optLong("syncedAt")
    if (syncedAt <= 0 || now - syncedAt !in 0L until MAX_SAVED_AGE) return null
    val tasks = data.optJSONArray("tasks") ?: return null
    return SavedLaundry(tasks.objects().take(MAX_SAVED_TASKS).map { row ->
        LaundryTask.parse(row).copy(clockOffsetMs = row.optLong("clockOffsetMs"))
    }.filter { it.id.isNotBlank() && it.machineId.isNotBlank() && !it.isExpired(now + it.clockOffsetMs) }, syncedAt)
}
