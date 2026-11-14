package com.laoke.weshirt.platform.widget

internal enum class WidgetFreshness { MISSING, INVALID_TIME, OUTDATED, RECENT }
internal enum class WidgetTaskState { NONE, ESTIMATED, AWAITING_CONFIRMATION, UNVERIFIED, READY_FOR_PICKUP }
internal enum class WidgetSize { COMPACT, WIDE, EXPANDED }

/** Time estimates never establish that a machine is idle or a task has ended. */
internal object LaundryWidgetState {
    private const val STALE_AFTER_MILLIS = 10 * 60_000L
    private const val FUTURE_TOLERANCE_MILLIS = 30_000L

    fun freshness(snapshot: LaundryWidgetSnapshot?, now: Long): WidgetFreshness {
        if (snapshot == null) return WidgetFreshness.MISSING
        val updated = snapshot.updatedAtMillis
        if (updated <= 0L || updated > now && updated - now > FUTURE_TOLERANCE_MILLIS) return WidgetFreshness.INVALID_TIME
        return if (now >= updated && now - updated >= STALE_AFTER_MILLIS) WidgetFreshness.OUTDATED else WidgetFreshness.RECENT
    }

    fun taskState(snapshot: LaundryWidgetSnapshot?, now: Long): WidgetTaskState {
        if (snapshot == null) return WidgetTaskState.NONE
        if (snapshot.taskUnverified || freshness(snapshot, now) != WidgetFreshness.RECENT) return WidgetTaskState.UNVERIFIED
        if (snapshot.taskMachineName.isNullOrBlank()) return WidgetTaskState.NONE
        if (snapshot.taskCompletionConfirmed) return WidgetTaskState.READY_FOR_PICKUP
        val finish = snapshot.taskEstimatedFinishAtMillis?.takeIf { it > 0L } ?: return WidgetTaskState.UNVERIFIED
        return if (finish <= now) WidgetTaskState.AWAITING_CONFIRMATION else WidgetTaskState.ESTIMATED
    }

    fun remainingMinutes(snapshot: LaundryWidgetSnapshot?, now: Long): Int? = when (taskState(snapshot, now)) {
        WidgetTaskState.READY_FOR_PICKUP, WidgetTaskState.AWAITING_CONFIRMATION -> 0
        WidgetTaskState.ESTIMATED -> ((snapshot!!.taskEstimatedFinishAtMillis!! - now - 1) / 60_000L + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        else -> null
    }

    /** Missing or inconsistent start times stay indeterminate instead of inventing a percentage. */
    fun progressPercent(snapshot: LaundryWidgetSnapshot?, now: Long): Int? {
        val state = taskState(snapshot, now)
        if (state == WidgetTaskState.READY_FOR_PICKUP) return 100
        if (state != WidgetTaskState.ESTIMATED && state != WidgetTaskState.AWAITING_CONFIRMATION) return null
        val start = snapshot?.taskStartedAtMillis?.takeIf { it > 0L } ?: return null
        val finish = snapshot.taskEstimatedFinishAtMillis?.takeIf { it > start } ?: return null
        if (start > now) return null
        return (((now - start).toDouble() / (finish - start).toDouble()) * 100).toInt().coerceIn(0, 100)
    }

    fun isRunning(snapshot: LaundryWidgetSnapshot?, now: Long): Boolean = taskState(snapshot, now) == WidgetTaskState.ESTIMATED

    fun size(width: Int, height: Int, fontScale: Float): WidgetSize = when {
        width >= 230 && height >= 320 && fontScale <= 1.5f -> WidgetSize.EXPANDED
        width >= 230 && height >= 140 && fontScale <= 1.5f -> WidgetSize.WIDE
        else -> WidgetSize.COMPACT
    }

    fun safeLabel(value: String): String = value.filterNot(Char::isISOControl).trim().take(100)
}
