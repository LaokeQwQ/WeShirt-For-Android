package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.update.UpdateDownloadMeter
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadMetricsTest {
    @Test fun transferRateUsesRecentBytesAndEtaDisappearsWhenNetworkStops() {
        val meter = UpdateDownloadMeter()
        assertNull(meter.sample(0, 10_000, 1000).remainingSeconds)
        val moving = meter.sample(2000, 10_000, 2000)
        assertEquals(2000L, moving.bytesPerSecond)
        assertEquals(4L, moving.remainingSeconds)
        assertEquals(.2f, moving.fraction, .001f)
        repeat(6) { meter.sample(2000, 10_000, 3000L + it * 1000) }
        val stalled = meter.sample(2000, 10_000, 9000)
        assertEquals(0L, stalled.bytesPerSecond)
        assertNull(stalled.remainingSeconds)
        assertTrue(stalled.description().contains("等待网络"))
    }

    @Test fun cachedFileReplacementOrClockResetCannotProduceNegativeSpeedOrMadeUpEta() {
        val meter = UpdateDownloadMeter()
        meter.sample(5000, 10_000, 2000)
        val restarted = meter.sample(0, 10_000, 3000)
        assertEquals(0L, restarted.bytesPerSecond)
        assertNull(restarted.remainingSeconds)
        val rollback = meter.sample(2000, 10_000, 1000)
        assertEquals(0L, rollback.bytesPerSecond)
        assertNull(rollback.remainingSeconds)
        assertNull(meter.sample(10_000, 10_000, 2000).remainingSeconds)
    }
}
