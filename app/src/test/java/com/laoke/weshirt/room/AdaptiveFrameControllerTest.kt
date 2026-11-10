package com.laoke.weshirt.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveFrameControllerTest {
    @Test fun startsAtFullResolutionAndUsesTheDisplayCeiling() {
        val controller = AdaptiveFrameController(144f)
        controller.setActivity(interacting = true, animated = true)
        assertEquals(120, controller.state().targetFps)
        assertEquals(1f, controller.state().resolutionScale)
        assertEquals(0, controller.state().adaptiveLevel)
        controller.setDisplayRefreshRate(90f)
        assertEquals(90, controller.state().targetFps)
        controller.setDisplayRefreshRate(48f)
        assertEquals(48, controller.state().targetFps)
    }

    @Test fun oneStutterDoesNotMakeTheRoomPermanentlyBlurry() {
        val controller = activeController()
        controller.observeFrame(0, 90_000_000, 2_000_000, BUDGET)
        feed(controller, 16_666_667, 5, work = 3_000_000)
        assertEquals(0, controller.state().adaptiveLevel)
        assertEquals(1f, controller.state().resolutionScale)
    }

    @Test fun sustainedGpuPressureReducesFpsBeforeResolutionAndHonorsCooldown() {
        val controller = activeController(120f)
        feed(controller, 0, 3, work = 25_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        assertEquals(90, controller.state().targetFps)
        assertEquals(1f, controller.state().resolutionScale)
        assertTrue(controller.state(consumeNotice = true).showDowngradeNotice)
        assertFalse(controller.state().showDowngradeNotice)
        feed(controller, 3_000_000_000, 4, work = 25_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        feed(controller, 7_000_000_000, 5, work = 25_000_000)
        assertEquals(2, controller.state().adaptiveLevel)
        assertEquals(60, controller.state().targetFps)
        assertEquals(.9f, controller.state().resolutionScale)
    }

    @Test fun intentionalThirtyFpsIntervalsAreNotReportedAsDroppedFrames() {
        val controller = activeController(120f)
        for (frame in 0..400) controller.observeFrame(
            nowNanos = frame * 33_333_333L,
            gpuDurationNanos = 3_000_000,
            cpuDurationNanos = 1_000_000,
            scheduledBudgetNanos = 33_333_333,
        )
        assertEquals(0, controller.state().adaptiveLevel)
    }

    @Test fun recoveredDeviceMustStayHealthyBeforeQualityReturns() {
        val controller = activeController(90f)
        feed(controller, 0, 3, work = 25_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        feed(controller, 3_000_000_000, 10, work = 3_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        feed(controller, 13_000_000_000, 12, work = 3_000_000)
        assertEquals(0, controller.state().adaptiveLevel)
        assertEquals(90, controller.state().targetFps)
    }

    @Test fun backgroundHasNoFramesAndDoesNotPolluteTheNextSession() {
        val controller = activeController()
        controller.setForeground(false)
        feed(controller, 0, 20, work = 100_000_000)
        assertEquals(0, controller.state().targetFps)
        assertEquals(QualityReason.BACKGROUND, controller.state().reason)
        controller.setForeground(true)
        assertEquals(60, controller.state().targetFps)
        assertEquals(0, controller.state().adaptiveLevel)
    }

    @Test fun powerAndThermalCapsOverrideManualModesWithoutTrainingTheAdaptivePolicy() {
        val controller = activeController(120f)
        controller.setMode(PerformanceMode.HIGH)
        controller.setPowerSaver(true)
        assertEquals(30, controller.state().targetFps)
        controller.setPowerSaver(false)
        controller.setThermalStatus(3)
        assertEquals(30, controller.state().targetFps)
        assertEquals(.8f, controller.state().resolutionScale)
        assertEquals(QualityReason.THERMAL, controller.state().reason)
        feed(controller, 0, 20, work = 100_000_000)
        controller.setThermalStatus(0)
        assertEquals(120, controller.state().targetFps)
        assertEquals(1f, controller.state().resolutionScale)
        assertEquals(0, controller.state().adaptiveLevel)
    }

    @Test fun rendererBackpressureIsARealPressureSignalEvenWithoutGpuTimestamps() {
        val controller = activeController()
        for (frame in 0..180) controller.observeFrame(frame * BUDGET, null, 1_000_000, BUDGET, rendererBusy = true)
        assertTrue(controller.state().adaptiveLevel > 0)
    }

    private fun activeController(refreshRate: Float = 60f) = AdaptiveFrameController(refreshRate).apply {
        setActivity(interacting = true, animated = true)
    }

    private fun feed(controller: AdaptiveFrameController, start: Long, seconds: Int, work: Long) {
        for (frame in 0 until seconds * 60) controller.observeFrame(start + frame * BUDGET, work, 1_000_000, BUDGET)
    }

    companion object { private const val BUDGET = 16_666_667L }
}
