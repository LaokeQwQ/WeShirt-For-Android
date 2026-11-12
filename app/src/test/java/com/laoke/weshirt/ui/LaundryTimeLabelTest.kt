package com.laoke.weshirt.ui

import com.laoke.weshirt.data.LaundryTask
import org.junit.Assert.*
import org.junit.Test

class LaundryTimeLabelTest {
    private fun task(end: Long?, done: Boolean = false) = LaundryTask("t", "m", "洗衣机", "washer", end, done, "", false, false, false)

    @Test fun timerExpiryNeverClaimsMachineIsIdle() {
        val now = 10_000_000L
        assertEquals("预计剩余 2 分钟", laundryTimeLabel(task(now + 60_001), now))
        assertEquals("登记计时已到 · 请现场确认", laundryTimeLabel(task(now), now))
        assertEquals("登记计时结束3 天 · 请确认机器和衣物", laundryTimeLabel(task(now - 3 * 86_400_000), now))
        assertEquals("结束时间待确认", laundryTimeLabel(task(null), now))
        assertEquals("已确认取衣", laundryTimeLabel(task(now, true), now))
    }
    @Test fun platformTimeAndServerClockWinOverLocalClockAndOnlyTelemetryConfirmsCompletion() {
        val now = 10_000_000L
        val platform = task(now + 4 * 60_000).copy(timingSource = "upstream", clockOffsetMs = 2 * 60_000)
        assertEquals("平台剩余 2 分钟", laundryTimeLabel(platform, now))
        assertEquals("即将完成", laundryTimeLabel(platform.copy(endAt = now), now))
        assertEquals("平台已确认完成 · 等待取衣", laundryTimeLabel(platform.copy(completionConfirmed = true), now))
        val machine = com.laoke.weshirt.data.Machine("m", "洗衣机", "1", "washer", "occupied", now + 4 * 60_000, "", false, false, "", clockOffsetMs = 2 * 60_000)
        assertEquals(2L, machine.minutes(now))
    }
}
