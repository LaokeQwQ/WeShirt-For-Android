package com.laoke.weshirt.platform.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.isArchived
import com.laoke.weshirt.data.isExpired
import com.laoke.weshirt.data.serviceEndpoint
import com.laoke.weshirt.data.str
import org.json.JSONObject
import java.security.MessageDigest

/** Only display data: no account details, credentials, remarks, or occupant identity. */
data class LaundryWidgetSnapshot(
    val buildingName: String,
    val idle: Int,
    val busy: Int,
    val unknown: Int,
    val updatedAtMillis: Long,
    val taskMachineName: String? = null,
    val taskEstimatedFinishAtMillis: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskId: String? = null,
    val taskKind: String? = null,
    val taskUnverified: Boolean = false,
    val taskCompletionConfirmed: Boolean = false,
)

/** Runtime identity. Only its digest is persisted alongside the display snapshot. */
data class LaundryWidgetScope internal constructor(val key: String, val origin: String, val userId: String) {
    override fun toString(): String = key
}

internal fun laundryWidgetScope(saved: JSONObject?): LaundryWidgetScope? {
    saved ?: return null
    val origin = saved.str("origin").trimEnd('/')
    val userId = saved.optJSONObject("user")?.str("id").orEmpty()
        .ifBlank { saved.optJSONObject("profile")?.str("id").orEmpty() }
    val profileId = saved.optJSONObject("profile")?.str("id").orEmpty()
    val access = saved.str("accessToken")
    if (origin.isBlank() || userId.isBlank() || access.isBlank() || profileId.isNotBlank() && profileId != userId) return null
    val pieces = access.split('.')
    // Native refresh rotates secrets, but the server session ID stays stable.
    val identity = if (pieces.size == 3 && pieces[0] == "ma1" && pieces[1].isNotBlank()) "ma1:${pieces[1]}" else access
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$origin\u0000$userId\u0000$identity".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    return LaundryWidgetScope(digest, origin, userId)
}

/** Both foreground refresh and the background widget worker use this projection. */
fun projectLaundryWidget(state: AppState, now: Long = System.currentTimeMillis()): LaundryWidgetSnapshot? {
    val profile = state.profile ?: return null
    if (!state.authReady || profile.id.isBlank() || state.building.isBlank()) return null
    val permissions = state.access.optJSONObject("permissions") ?: return null
    val mayReadTasks = permissions.optJSONObject("tasks")?.optBoolean("view", true) != false
    val tasks = if (mayReadTasks) state.tasks.filter {
        val serverNow = now + it.clockOffsetMs
        !it.done && !it.isArchived(serverNow) && !it.isExpired(serverNow)
    } else emptyList()
    val running = tasks.filterNot { it.completionConfirmed }
    val task = running.filter { (it.endAt?.minus(it.clockOffsetMs) ?: Long.MIN_VALUE) > now }
        .minByOrNull { it.endAt!!.minus(it.clockOffsetMs) }
        ?: running.maxByOrNull { it.endAt?.minus(it.clockOffsetMs) ?: Long.MIN_VALUE }
        ?: tasks.maxByOrNull { it.endAt?.minus(it.clockOffsetMs) ?: Long.MIN_VALUE }
    val clockOffset = state.machines.firstOrNull()?.clockOffsetMs ?: task?.clockOffsetMs ?: 0L
    val boardUpdated = state.updatedAt?.minus(clockOffset)
    val verifiedAt = if (mayReadTasks) state.tasksUpdatedAt else now
    val updated = listOfNotNull(boardUpdated, verifiedAt).minOrNull() ?: 0L
    return LaundryWidgetSnapshot(
        buildingName = state.buildings.find { it.code == state.building }?.name ?: "当前宿舍楼",
        idle = state.machines.count { it.state == "idle" },
        busy = state.machines.count { it.state == "occupied" },
        unknown = state.machines.count { it.state != "idle" && it.state != "occupied" },
        updatedAtMillis = updated,
        taskMachineName = task?.let { row ->
            val machine = state.machines.find { it.id == row.machineId }
            val name = machine?.name?.takeIf(String::isNotBlank) ?: row.name
            val slot = machine?.slot?.let(LaundryWidgetState::safeLabel).orEmpty()
            val kind = if (row.kind == "dryer") "烘干机" else "洗衣机"
            when {
                slot.isBlank() -> name.ifBlank { kind }
                name.startsWith("${slot}号") || name.startsWith("$slot 号") -> name
                name.isBlank() -> "$slot 号$kind"
                else -> "$slot 号$kind · $name"
            }
        },
        taskEstimatedFinishAtMillis = task?.endAt?.minus(task.clockOffsetMs),
        taskStartedAtMillis = task?.startAt?.takeIf { it > 0 }?.minus(task.clockOffsetMs),
        taskId = task?.id,
        taskKind = task?.kind,
        taskUnverified = !mayReadTasks || state.boardFromCache || state.boardWarning != null || state.tasksRefreshFailed || verifiedAt == null,
        taskCompletionConfirmed = task?.completionConfirmed == true,
    )
}

object LaundryWidgetStore {
    private const val PREFS = "laundry_widget_snapshot"

    fun captureScope(context: Context): LaundryWidgetScope? {
        val scope = laundryWidgetScope(SecureSession(context).read()) ?: return null
        val origin = serviceEndpoint(BuildConfig.DEBUG, BuildConfig.API_BASE_URL,
            context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("endpoint", null)).trimEnd('/')
        return scope.takeIf { it.origin == origin }
    }

    /** Capture scope before doing network work; a different login can never receive that work's result. */
    @Synchronized fun update(context: Context, snapshot: LaundryWidgetSnapshot,
        expectedScope: LaundryWidgetScope? = captureScope(context)): Boolean {
        if (expectedScope == null || captureScope(context) != expectedScope) return false
        if (!acceptWidgetSnapshot(read(context), snapshot)) return false
        val saved = encodeWidgetSnapshot(snapshot)
        val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("scope", expectedScope.key).putString("snapshot", saved.toString()).remove("error").commit()
        if (committed) refresh(context)
        return committed
    }

    @Synchronized internal fun read(context: Context): LaundryWidgetSnapshot? {
        val scope = captureScope(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("scope", null) != scope.key) return null
        return prefs.getString("snapshot", null)?.let { raw -> runCatching { decodeWidgetSnapshot(JSONObject(raw)) }.getOrNull() }
    }

    /** Permission revocation may arrive with an older board; remove private task data regardless. */
    @Synchronized fun redactTask(context: Context, expectedScope: LaundryWidgetScope?): Boolean {
        if (expectedScope == null || captureScope(context) != expectedScope) return false
        val old = read(context) ?: return true
        return update(context, old.copy(taskMachineName = null, taskEstimatedFinishAtMillis = null,
            taskStartedAtMillis = null, taskId = null, taskKind = null, taskUnverified = true,
            taskCompletionConfirmed = false), expectedScope)
    }

    /** The caller supplies only a safe status/event description, never an API response body. */
    @Synchronized fun setError(context: Context, error: String, expectedScope: LaundryWidgetScope?): Boolean {
        if (expectedScope == null || captureScope(context) != expectedScope) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = if (prefs.getString("scope", null) == expectedScope.key) read(context) else null
        val edit = prefs.edit().putString("scope", expectedScope.key)
            .putString("error", LaundryWidgetState.safeLabel(error))
        if (old == null) edit.remove("snapshot") else edit.putString("snapshot", encodeWidgetSnapshot(old.copy(taskUnverified = true)).toString())
        val committed = edit.commit()
        if (committed) refresh(context)
        return committed
    }

    @Synchronized fun refreshError(context: Context): String? {
        val scope = captureScope(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("error", null)?.takeIf { prefs.getString("scope", null) == scope.key }
    }

    @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        refresh(context)
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, LaundryWidgetProvider::class.java))
        LaundryWidgetProvider().onUpdate(context, manager, ids)
    }
}

/** A response started earlier may finish last, in either the app or its widget worker. */
internal fun acceptWidgetSnapshot(previous: LaundryWidgetSnapshot?, incoming: LaundryWidgetSnapshot): Boolean =
    previous == null || incoming.updatedAtMillis >= previous.updatedAtMillis

internal fun encodeWidgetSnapshot(snapshot: LaundryWidgetSnapshot): JSONObject = JSONObject()
    .put("building", LaundryWidgetState.safeLabel(snapshot.buildingName))
    .put("idle", snapshot.idle.coerceAtLeast(0)).put("busy", snapshot.busy.coerceAtLeast(0)).put("unknown", snapshot.unknown.coerceAtLeast(0))
    .put("updated", snapshot.updatedAtMillis).put("machine", snapshot.taskMachineName?.let(LaundryWidgetState::safeLabel))
    .put("finish", snapshot.taskEstimatedFinishAtMillis).put("start", snapshot.taskStartedAtMillis)
    .put("task", snapshot.taskId?.take(128)).put("kind", snapshot.taskKind?.takeIf { it in setOf("washer", "dryer") })
    .put("unverified", snapshot.taskUnverified).put("confirmed", snapshot.taskCompletionConfirmed)

internal fun decodeWidgetSnapshot(value: JSONObject) = LaundryWidgetSnapshot(
    LaundryWidgetState.safeLabel(value.str("building")), value.optInt("idle").coerceAtLeast(0),
    value.optInt("busy").coerceAtLeast(0), value.optInt("unknown").coerceAtLeast(0), value.optLong("updated"),
    value.str("machine").takeIf(String::isNotBlank)?.let(LaundryWidgetState::safeLabel),
    if (value.isNull("finish")) null else value.optLong("finish"),
    if (value.isNull("start")) null else value.optLong("start"),
    value.str("task").takeIf(String::isNotBlank)?.take(128),
    value.str("kind").takeIf { it in setOf("washer", "dryer") },
    value.optBoolean("unverified"), value.optBoolean("confirmed"),
)
