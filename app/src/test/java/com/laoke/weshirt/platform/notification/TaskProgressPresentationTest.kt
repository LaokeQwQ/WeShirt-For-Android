package com.laoke.weshirt.platform.notification

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class TaskProgressPresentationTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
    private fun task() = TaskProgress("task", 1, "一层 · 2号机", now - 20 * 60_000, now + 20 * 60_000,
        userRequestedTracking = true, kind = "dryer")

    @Test fun dryingShowsMachineEtaAndEstimatedProgressInsteadOfAlwaysSayingWashing() {
        val display = task().presentation(now, ZoneOffset.UTC)
        assertEquals("一层 · 2号机 · 烘干中", display.title)
        assertTrue(display.text.contains("12:20"))
        assertEquals(500, display.progress)
        assertFalse(display.indeterminate)
        assertFalse(display.expectedEnded)
        assertEquals(750, task().presentation(now + 10 * 60_000, ZoneOffset.UTC).progress)
    }

    @Test fun knownDurationCanSupplyEstimatedStartButAnUnknownDurationCannotInventAPercent() {
        val withDuration = task().copy(startedAtMillis = 0, durationMinutes = 40).presentation(now, ZoneOffset.UTC)
        assertEquals(500, withDuration.progress)
        assertFalse(withDuration.indeterminate)
        val unknown = task().copy(startedAtMillis = 0).presentation(now, ZoneOffset.UTC)
        assertTrue(unknown.indeterminate)
        assertTrue(unknown.text.contains("进度待确认"))
        assertEquals(0, unknown.progress)
    }

    @Test fun elapsedEstimateWaitsForConfirmationInsteadOfClaimingCompleteOrShowing100Percent() {
        val display = task().presentation(now + 20 * 60_000, ZoneOffset.UTC)
        assertTrue(display.expectedEnded)
        assertTrue(display.indeterminate)
        assertEquals(0, display.progress)
        assertTrue(display.title.endsWith("等待确认"))
        assertEquals("预计时间已到，设备尚未确认完成", display.text)
    }

    @Test fun queueAndSelfCleanUseTheirOwnStateAndFutureTasksCannotBePromotedAsRunning() {
        val queued = task().copy(taskId = "queue:reservation", startedAtMillis = 0).presentation(now, ZoneOffset.UTC)
        assertTrue(queued.title.endsWith("等候中"))
        assertTrue(queued.text.startsWith("上一位预计"))
        assertTrue(queued.indeterminate)
        val clean = task().copy(kind = "washer", program = "self_clean").presentation(now, ZoneOffset.UTC)
        assertTrue(clean.title.endsWith("桶自洁中"))
        val future = task().copy(startedAtMillis = now + 60_000).presentation(now, ZoneOffset.UTC)
        assertFalse(future.hasStarted)
        assertTrue(future.indeterminate)
        assertTrue(future.title.endsWith("等待开始"))
    }
}
