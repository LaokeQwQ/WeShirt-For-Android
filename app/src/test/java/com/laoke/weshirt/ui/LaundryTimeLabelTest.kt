package com.laoke.weshirt.ui

import com.laoke.weshirt.data.LaundryTask
import org.junit.Assert.*
import org.junit.Test

class LaundryTimeLabelTest {
    private fun task(end: Long?, done: Boolean = false) = LaundryTask("t", "m", "洗衣机", "washer", end, done, "", false, false, false)

    @Test fun timerExpiryNeverClaimsMachineIsIdle() {
        val now = 10_000_000L
        assertEquals("登记计时剩余 2 分钟", laundryTimeLabel(task(now + 60_001), now))
        assertEquals("登记计时已到 · 请现场确认", laundryTimeLabel(task(now), now))
        assertEquals("登记计时结束3 天 · 请确认机器和衣物", laundryTimeLabel(task(now - 3 * 86_400_000), now))
        assertEquals("结束时间待确认", laundryTimeLabel(task(null), now))
        assertEquals("已确认取衣", laundryTimeLabel(task(now, true), now))
    }
}
