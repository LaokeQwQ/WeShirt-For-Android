package com.laoke.weshirt.platform.widget

internal enum class WidgetFreshness { MISSING, INVALID_TIME, OUTDATED, RECENT }
internal enum class WidgetTaskState { NONE, ESTIMATED, AWAITING_CONFIRMATION, UNVERIFIED }
internal enum class WidgetSize { COMPACT, WIDE, EXPANDED }

/** Time estimates never establish that a machine is idle or a task has ended. */
internal object LaundryWidgetState {
    private const val STALE_AFTER_MILLIS = 10 * 60_000L
    private const val FUTURE_TOLERANCE_MILLIS = 30_000L

    fun freshness(snapshot: LaundryWidgetSnapshot?, now: Long): WidgetFreshness {
        if (snapshot == null) return WidgetFreshness.MISSING
        val updated = snapshot.updatedAtMillis
        if (updated <= 0L || updated > now && updated - now > FUTURE_TOLERANCE_MILLIS) return WidgetFreshness.INVALID_TIME
        return if (now > updated && now - updated > STALE_AFTER_MILLIS) WidgetFreshness.OUTDATED else WidgetFreshness.RECENT
    }

    fun taskState(snapshot: LaundryWidgetSnapshot?, now: Long): WidgetTaskState {
        if (snapshot?.taskMachineName.isNullOrBlank()) return WidgetTaskState.NONE
        if (freshness(snapshot, now) != WidgetFreshness.RECENT) return WidgetTaskState.UNVERIFIED
        val finish = snapshot?.taskEstimatedFinishAtMillis?.takeIf { it > 0L } ?: return WidgetTaskState.UNVERIFIED
        return if (finish <= now) WidgetTaskState.AWAITING_CONFIRMATION else WidgetTaskState.ESTIMATED
    }

    fun size(width: Int, height: Int, fontScale: Float): WidgetSize = when {
        width >= 230 && height >= 320 && fontScale <= 1.5f -> WidgetSize.EXPANDED
        width >= 230 && height >= 140 && fontScale <= 1.5f -> WidgetSize.WIDE
        else -> WidgetSize.COMPACT
    }

    fun safeLabel(value: String): String = value.filterNot(Char::isISOControl).trim().take(100)
}
