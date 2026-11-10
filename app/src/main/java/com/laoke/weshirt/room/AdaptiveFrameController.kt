package com.laoke.weshirt.room

import kotlin.math.max
import kotlin.math.min

/**
 * Policy only: no Android clock, timers or display APIs. Samples describe work on a rendered
 * frame, not the wall time between intentionally throttled frames. GPU timing is preferred;
 * CPU duration and renderer backpressure cover devices without GPU timestamp support.
 */
class AdaptiveFrameController(displayRefreshRate: Float = 60f) {
    var mode: PerformanceMode = PerformanceMode.AUTO
        private set
    var foreground = true
        private set
    private var refreshRate = validRefreshRate(displayRefreshRate)
    private var interacting = false
    private var animated = false
    private var powerSaver = false
    private var thermalStatus = 0
    private var adaptiveLevel = 0
    private var windowStart = -1L
    private var frames = 0
    private var badFrames = 0
    private var recoveryFrames = 0
    private var goodSince = -1L
    private var lastChange = Long.MIN_VALUE / 2
    private var noticePending = false

    fun setMode(value: PerformanceMode) {
        if (mode != value) {
            mode = value
            adaptiveLevel = 0
            noticePending = false
            lastChange = Long.MIN_VALUE / 2
            resetWindow()
        }
    }

    fun setDisplayRefreshRate(value: Float) {
        val next = validRefreshRate(value)
        if(next != refreshRate) { refreshRate = next; resetWindow() }
    }
    fun setActivity(interacting: Boolean, animated: Boolean) {
        if ((this.interacting || this.animated) != (interacting || animated)) resetWindow()
        this.interacting = interacting
        this.animated = animated
    }

    fun setForeground(value: Boolean) {
        if (foreground != value) {
            foreground = value
            resetWindow()
        }
    }

    fun setPowerSaver(value: Boolean) {
        if(powerSaver != value) { powerSaver = value; resetWindow() }
    }

    fun setThermalStatus(value: Int) {
        val previous = thermalStatus
        thermalStatus = value.coerceIn(0, 6)
        if((previous >= 2) != (thermalStatus >= 2)) resetWindow()
        if (previous < 3 && thermalStatus >= 3) noticePending = true
    }

    fun state(consumeNotice: Boolean = false): QualityState {
        val deviceCap = supportedCap(refreshRate)
        var fps = when (mode) {
            PerformanceMode.BATTERY -> min(deviceCap, 30)
            PerformanceMode.SMOOTH -> min(deviceCap, 60)
            PerformanceMode.AUTO, PerformanceMode.HIGH -> deviceCap
        }
        var scale = 1f
        var reason = QualityReason.DEVICE
        if (mode == PerformanceMode.AUTO) {
            val ladder = listOf(deviceCap, 90, 60, 30).filter { it <= deviceCap }.distinct()
            fps = ladder[min(adaptiveLevel, ladder.lastIndex)]
            scale = when (adaptiveLevel) { 0, 1 -> 1f; 2 -> 0.9f; else -> 0.8f }
            if (adaptiveLevel > 0) reason = QualityReason.PERFORMANCE
        }
        if (!interacting && !animated) {
            fps = min(fps, 30)
            if (reason == QualityReason.DEVICE) reason = QualityReason.IDLE
        }
        if (powerSaver || mode == PerformanceMode.BATTERY) {
            fps = min(fps, 30)
            reason = QualityReason.POWER_SAVER
        }
        if (thermalStatus >= 2) {
            fps = min(fps, if (thermalStatus >= 3) 30 else 60)
            if (thermalStatus >= 3) scale = min(scale, 0.8f)
            reason = QualityReason.THERMAL
        }
        if (!foreground) {
            fps = 0
            reason = QualityReason.BACKGROUND
        }
        val state = QualityState(fps, scale, reason, adaptiveLevel, noticePending)
        if (consumeNotice) noticePending = false
        return state
    }

    fun observeFrame(
        nowNanos: Long,
        gpuDurationNanos: Long?,
        cpuDurationNanos: Long,
        scheduledBudgetNanos: Long,
        rendererBusy: Boolean = false,
        missedVsyncNanos: Long = 0L,
    ) {
        if (!foreground || mode != PerformanceMode.AUTO || powerSaver || thermalStatus >= 2 ||
            (!interacting && !animated) || scheduledBudgetNanos <= 0L
        ) return
        if (windowStart < 0L) windowStart = nowNanos
        frames++
        val work = max(gpuDurationNanos?.takeIf { it > 0L } ?: 0L, cpuDurationNanos)
        val bad = rendererBusy || work > scheduledBudgetNanos * 0.9 ||
            missedVsyncNanos > scheduledBudgetNanos * 0.75
        if (bad) badFrames++
        if (!bad && hasRecoveryHeadroom(work)) recoveryFrames++
        if (nowNanos - windowStart < WINDOW_NANOS || frames < MIN_WINDOW_FRAMES) return

        val badRatio = badFrames.toDouble() / frames
        when {
            badRatio >= 0.25 -> {
                goodSince = -1L
                if (nowNanos - lastChange >= DOWNGRADE_COOLDOWN && adaptiveLevel < MAX_LEVEL) {
                    adaptiveLevel++
                    lastChange = nowNanos
                    noticePending = true
                }
            }
            badRatio <= 0.05 && recoveryFrames.toDouble() / frames >= 0.9 -> {
                if (goodSince < 0L) goodSince = windowStart
                if (adaptiveLevel > 0 && nowNanos - goodSince >= RECOVERY_NANOS &&
                    nowNanos - lastChange >= DOWNGRADE_COOLDOWN
                ) {
                    adaptiveLevel--
                    lastChange = nowNanos
                    goodSince = nowNanos
                }
            }
            else -> goodSince = -1L
        }
        windowStart = nowNanos
        frames = 0
        badFrames = 0
        recoveryFrames = 0
    }

    private fun resetWindow() {
        windowStart = -1L
        frames = 0
        badFrames = 0
        recoveryFrames = 0
        goodSince = -1L
    }

    private fun hasRecoveryHeadroom(workNanos: Long): Boolean {
        if(adaptiveLevel == 0) return true
        val deviceCap = supportedCap(refreshRate)
        val ladder = listOf(deviceCap, 90, 60, 30).filter { it <= deviceCap }.distinct()
        val currentScale = scaleFor(adaptiveLevel)
        val nextScale = scaleFor(adaptiveLevel - 1)
        val nextFps = ladder[min(adaptiveLevel - 1, ladder.lastIndex)]
        // Require enough headroom for the next quality step, not just for the reduced
        // frame rate. Otherwise a 20 ms device would repeatedly bounce between 30 and 60 FPS.
        val estimatedWork = workNanos * (nextScale / currentScale) * (nextScale / currentScale)
        return estimatedWork < (1_000_000_000.0 / nextFps) * .75
    }

    private fun scaleFor(level: Int) = when(level) { 0, 1 -> 1f; 2 -> .9f; else -> .8f }

    companion object {
        private const val WINDOW_NANOS = 2_000_000_000L
        private const val DOWNGRADE_COOLDOWN = 8_000_000_000L
        private const val RECOVERY_NANOS = 16_000_000_000L
        private const val MIN_WINDOW_FRAMES = 20
        private const val MAX_LEVEL = 3

        internal fun supportedCap(refreshRate: Float): Int = when {
            refreshRate >= 119f -> 120
            refreshRate >= 89f -> 90
            refreshRate >= 59f -> 60
            else -> refreshRate.toInt().coerceAtLeast(1)
        }

        private fun validRefreshRate(value: Float): Float =
            value.takeIf { it.isFinite() && it >= 1f } ?: 60f
    }
}
