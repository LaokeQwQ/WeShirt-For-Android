package com.laoke.weshirt.platform.notification

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TaskProgress(
    val taskId: String,
    val revision: Long,
    val machineName: String,
    val startedAtMillis: Long,
    val estimatedFinishAtMillis: Long,
    val active: Boolean = true,
    /** Promotion requires the user's explicit choice to monitor this particular active task. */
    val userRequestedTracking: Boolean = false,
    /** Zero keeps the finish reminder while disabling the advance reminder. */
    val leadMinutes: Int = 3,
    val kind: String = "washer",
    val program: String = "",
    /** A known cycle length can supply an estimated start when upstream only returns an ETA. */
    val durationMinutes: Int? = null,
)

internal data class TaskProgressPresentation(
    val title: String,
    val text: String,
    val progress: Int,
    val indeterminate: Boolean,
    val expectedEnded: Boolean,
    val hasStarted: Boolean,
)

/** Only a known start/duration yields a percentage. Reaching an ETA is never device confirmation. */
internal fun TaskProgress.presentation(now: Long, zone: ZoneId = ZoneId.systemDefault()): TaskProgressPresentation {
    val queued = taskId.startsWith("queue:")
    val activity = when {
        queued -> "等候中"
        program == "self_clean" -> "桶自洁中"
        kind == "dryer" -> "烘干中"
        else -> "洗衣中"
    }
    val machine = machineName.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(100)
        .ifBlank { if (kind == "dryer") "烘干机" else "洗衣机" }
    val start = startedAtMillis.takeIf { it > 0 } ?: durationMinutes?.takeIf { it in 1..1440 }
        ?.let { (estimatedFinishAtMillis - it * 60_000L).takeIf { value -> value > 0 } }
    val expectedEnded = now >= estimatedFinishAtMillis
    val hasStarted = start == null || now >= start
    val determinate = !expectedEnded && start != null && hasStarted && estimatedFinishAtMillis > start
    val progress = if (determinate && start != null) (((now - start) * 1000L) / (estimatedFinishAtMillis - start))
        .coerceIn(0L, 999L).toInt() else 0
    val end = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone)
        .format(Instant.ofEpochMilli(estimatedFinishAtMillis))
    val text = when {
        expectedEnded -> "预计时间已到 · 等待设备确认"
        !hasStarted -> "等待开始 · 预计 $end 结束"
        queued -> "前一项预计 $end 结束 · 空闲以设备确认为准"
        !determinate -> "预计 $end 结束 · 进度待确认"
        else -> "预计 $end 结束 · 以设备确认为准"
    }
    val title = "$machine · ${if (expectedEnded) "等待确认" else if (!hasStarted) "等待开始" else activity}"
    return TaskProgressPresentation(title, text, progress, !determinate, expectedEnded, hasStarted)
}
