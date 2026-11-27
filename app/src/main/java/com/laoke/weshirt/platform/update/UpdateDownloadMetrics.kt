package com.laoke.weshirt.platform.update

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ceil

internal data class UpdateDownloadMetrics(val bytes: Long, val total: Long, val bytesPerSecond: Long = 0,
                                         val remainingSeconds: Long? = null) {
    val fraction: Float get() = if (total > 0) (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
    fun description(): String = buildString {
        append("${size(bytes)} / ${size(total)}")
        append(" · ")
        append(if (bytesPerSecond > 0) "${size(bytesPerSecond)}/s" else "等待网络…")
        remainingSeconds?.let { append(" · 约${if (it < 60) "${it}秒" else "${ceil(it / 60.0).toLong()}分钟"}剩余") }
    }
    private fun size(value: Long) = if (value >= 1024 * 1024) String.format(Locale.getDefault(), "%.1f MB", value / (1024.0 * 1024))
        else "${value.coerceAtLeast(0) / 1024} KB"
}

/** A rolling window drops stale speed/ETA during a stalled connection. The caller uses monotonic time. */
internal class UpdateDownloadMeter {
    private data class Sample(val at: Long, val bytes: Long)
    private val samples = ArrayDeque<Sample>()
    fun sample(bytes: Long, total: Long, now: Long): UpdateDownloadMetrics {
        val count = bytes.coerceIn(0, total.coerceAtLeast(0))
        if (samples.lastOrNull()?.let { now < it.at || count < it.bytes } == true) samples.clear()
        samples.addLast(Sample(now, count))
        while (samples.size > 1 && samples.first.at < now - 5_000) samples.removeFirst()
        val first = samples.first
        val elapsed = now - first.at
        val speed = if (elapsed >= 500) ((count - first.bytes) * 1000L / elapsed).coerceAtLeast(0) else 0
        return UpdateDownloadMetrics(count, total, speed,
            if (speed > 0 && count < total) ceil((total - count).toDouble() / speed).toLong() else null)
    }
}
