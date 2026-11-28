package com.laoke.weshirt.ui

import com.laoke.weshirt.data.*
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

    @Test fun newerFreshRoomShowsTheSameRemainingTimeWhenHotWashExtendsItsDeadline() {
        val now = 10_000_000L
        val pending = task(now - 90_000).copy(timingSource = "upstream", currentTask = true,
            timingObservedAt = now - 120_000, createdAt = now - 30 * 60_000, takeState = "waiting")
        val machine = Machine("m", "洗衣机", "1", "washer", "occupied", now + 26 * 60_000 + 2_000,
            "", false, false, "", occupantId = "me", clockOffsetMs = 2_000,
            timingSource = "upstream", timingObservedAt = now - 15_000)
        val state = AppState(profile = Profile("me", "同学", "", "test", 0, "user"), machines = listOf(machine), tasks = listOf(pending))
        assertEquals("即将完成", laundryTimeLabel(pending, now))
        val reconciled = state.laundryDisplayTask(pending, now)
        assertEquals("平台剩余 26 分钟", laundryTimeLabel(reconciled, now))
        assertEquals(26L, machine.minutes(now))
        assertFalse(reconciled.canShowTake(now))
        assertEquals(machine.timingObservedAt, state.laundryTimingCatchUpAt(now))
        assertNull(state.copy(tasks = listOf(reconciled)).laundryTimingCatchUpAt(now))
    }

    @Test fun olderStaleAndForeignRunningObservationsCannotOverrideRegistration() {
        val now = 10_000_000L
        val pending = task(now - 90_000).copy(timingSource = "upstream", currentTask = true,
            timingObservedAt = now - 120_000, createdAt = now - 30 * 60_000, takeState = "waiting")
        val machine = Machine("m", "洗衣机", "1", "washer", "occupied", now + 26 * 60_000,
            "", false, false, "", occupantId = "me", timingSource = "upstream", timingObservedAt = now - 15_000)
        val state = AppState(profile = Profile("me", "同学", "", "test", 0, "user"), machines = listOf(machine), tasks = listOf(pending))
        val rejected = listOf(machine.copy(timingStale = true), machine.copy(timingObservedAt = now - 240_000),
            machine.copy(occupantId = "other"), machine.copy(timingSource = "user_report"))
        for (unsafe in rejected) {
            val changed = state.copy(machines = listOf(unsafe))
            assertEquals(pending, changed.laundryDisplayTask(pending, now))
            assertNull(changed.laundryTimingCatchUpAt(now))
        }
        assertEquals(pending, state.copy(boardFromCache = true).laundryDisplayTask(pending, now))
        val oldContract = pending.copy(currentTask = null)
        assertEquals(oldContract, state.laundryDisplayTask(oldContract, now))
        assertEquals(machine.timingObservedAt, state.copy(tasks = listOf(oldContract)).laundryTimingCatchUpAt(now))
        val completed = pending.copy(completionConfirmed = true)
        assertEquals(completed, state.laundryDisplayTask(completed, now))
    }

    @Test fun elapsedEstimateKeepsAReportedRunningMachineOutOfPickup() {
        val now = 10_000_000L
        val running = task(now - 90_000).copy(currentTask = true, machineState = "occupied",
            timingObservedAt = now - 10_000, takeState = "waiting")
        assertEquals("机器仍在运行 · 剩余时间待更新", laundryTimeLabel(running, now))
        assertFalse(running.canShowTake(now))
        assertNull(overdueCollectionCandidate(listOf(running), now))
        assertEquals("平台已确认完成 · 等待取衣", laundryTimeLabel(running.copy(completionConfirmed = true), now))
        assertTrue(running.copy(completionConfirmed = true).canShowTake(now))
    }
}
