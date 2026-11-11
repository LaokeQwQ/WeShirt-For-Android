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
        feed(controller, 0, 9, work = 3_000_000)
        controller.observeFrame(9_000_000_000, 90_000_000, 2_000_000, BUDGET)
        feed(controller, 9_016_666_667, 5, work = 3_000_000)
        assertEquals(0, controller.state().adaptiveLevel)
        assertEquals(1f, controller.state().resolutionScale)
    }

    @Test fun sustainedGpuPressureReducesFpsBeforeResolutionAndHonorsCooldown() {
        val controller = activeController(120f)
        feed(controller, 0, 14, work = 25_000_000)
        assertEquals(0, controller.state().adaptiveLevel)
        feed(controller, 14_000_000_000, 1, work = 25_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        assertEquals(90, controller.state().targetFps)
        assertEquals(1f, controller.state().resolutionScale)
        assertTrue(controller.state(consumeNotice = true).showDowngradeNotice)
        assertFalse(controller.state().showDowngradeNotice)
        feed(controller, 15_000_000_000, 28, work = 25_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        feed(controller, 43_000_000_000, 3, work = 25_000_000)
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
        feed(controller, 0, 15, work = 25_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        feed(controller, 15_000_000_000, 20, work = 3_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        feed(controller, 35_000_000_000, 12, work = 3_000_000)
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
        for (frame in 0..900) controller.observeFrame(frame * BUDGET, null, 1_000_000, BUDGET, rendererBusy = true)
        assertTrue(controller.state().adaptiveLevel > 0)
    }

    @Test fun separateShortInteractionsDoNotAccumulateIntoASustainedFailureWindow() {
        val controller = activeController()
        for (interaction in 0..20) {
            controller.setActivity(interacting = true, animated = false)
            val start = interaction * 10_000_000_000L
            for(frame in 0..30) controller.observeFrame(start + frame * BUDGET, 25_000_000, 1_000_000, BUDGET)
            controller.setActivity(interacting = false, animated = false)
        }
        assertEquals(0, controller.state().adaptiveLevel)
    }

    @Test fun healthyThirtyFpsDoesNotBounceBackToAnUnsustainableSixtyFps() {
        val controller = activeController()
        feed(controller, 0, 15, work = 20_000_000)
        assertEquals(1, controller.state().adaptiveLevel)
        for(frame in 0 until 30 * 30) controller.observeFrame(
            15_000_000_000L + frame * 33_333_333L, 20_000_000, 1_000_000, 33_333_333,
        )
        assertEquals(1, controller.state().adaptiveLevel)
        assertEquals(30, controller.state().targetFps)
    }

    @Test fun initialShaderCompilationAndTwoBadWindowsKeepFullQuality() {
        val controller = activeController(120f)
        feed(controller, 0, 8, work = 90_000_000)
        assertEquals(0, controller.state().adaptiveLevel)
        feed(controller, 8_000_000_000, 5, work = 90_000_000)
        assertEquals(0, controller.state().adaptiveLevel)
        assertFalse(controller.state().showDowngradeNotice)
    }

    private fun activeController(refreshRate: Float = 60f) = AdaptiveFrameController(refreshRate).apply {
        setActivity(interacting = true, animated = true)
    }

    private fun feed(controller: AdaptiveFrameController, start: Long, seconds: Int, work: Long) {
        for (frame in 0 until seconds * 60) controller.observeFrame(start + frame * BUDGET, work, 1_000_000, BUDGET)
    }

    companion object { private const val BUDGET = 16_666_667L }
}
