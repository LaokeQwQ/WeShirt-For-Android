package com.laoke.weshirt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineOrderingTest {
    private val now = 1_000_000L

    private fun machine(id: String, state: String, end: Long? = null, suspected: Boolean = false, name: String = id) =
        Machine(id, name, "1", "washer", state, end, "", false, false, "", suspectedUncollected = suspected)

    @Test fun availabilityGroupsMatchWebWithoutInferringIdleFromElapsedEstimates() {
        val input = listOf(
            machine("fault", "faulty"), machine("unknown", "unknown", now + 1),
            machine("late", "occupied", now + 120_000), machine("review", "abnormal", now + 1),
            machine("idle10", "idle", name = "洗衣机 10"), machine("stale", "occupied", now - 1),
            machine("pickup", "idle", suspected = true), machine("early", "occupied", now + 60_000),
            machine("idle2", "idle", name = "洗衣机 2")
        )
        val original = input.toList()
        val ids = sortMachinesByAvailability(input, now).map { it.id }
        assertEquals(listOf("idle2", "idle10", "early", "late"), ids.take(4))
        assertEquals(setOf("pickup", "review", "stale"), ids.subList(4, 7).toSet())
        assertEquals(listOf("unknown", "fault"), ids.takeLast(2))
        assertEquals(original, input)
    }

    @Test fun nameSortIsNumericAndStableForZeroPaddedNamesAndLongNumbers() {
        val input = listOf(
            machine("large", "idle", name = "机器 99999999999999999999999999999"),
            machine("ten", "idle", name = "机器 10"),
            machine("padded", "idle", name = "机器 002"),
            machine("two", "idle", name = "机器 2")
        )
        assertEquals(listOf("padded", "two", "ten", "large"), sortMachinesByAvailability(input, now).map { it.id })
    }

    @Test fun finishTimeOnlyOrdersRunningMachinesAndExactEndStaysUnconfirmed() {
        val items = listOf(
            machine("equal", "occupied", now), machine("unknown", "unknown", now + 10),
            machine("review", "abnormal", now + 20), machine("next", "occupied", now + 1),
            machine("missing", "occupied"), machine("idle", "idle", now + 100_000)
        )
        val result = sortMachinesByAvailability(items, now)
        assertEquals(listOf("idle", "next"), result.take(2).map { it.id })
        assertEquals("unknown", result.last().id)
        assertEquals(MachineBoardState.RUNNING, machineBoardStatus(items.first(), now).state)
        assertEquals("即将完成，等待设备确认", machineBoardStatus(items.first(), now).detail)
    }

    @Test fun onlyExplicitRunningStateExposesCountdown() {
        assertEquals("预计剩余 1 分钟", machineBoardStatus(machine("run", "occupied", now + 1), now).detail)
        assertEquals("预计剩余 2 分钟", machineBoardStatus(machine("run", "occupied", now + 60_001), now).detail)
        assertEquals("剩余时间待同步", machineBoardStatus(machine("run", "occupied"), now).detail)
        for (state in listOf("idle", "unknown", "faulty", "abnormal", "pending_pickup", "awaiting_pickup")) {
            val status = machineBoardStatus(machine(state, state, now + 90_000), now)
            assertFalse("A $state estimate must not become a countdown", status.detail.orEmpty().contains("分钟"))
        }
        assertNull(machineBoardStatus(machine("idle", "idle", now + 90_000), now).detail)
    }

    @Test fun suspicionOverridesIdleAndRunningPresentationAndNeverCountsAsAvailable() {
        for (state in listOf("idle", "occupied", "unknown")) {
            val status = machineBoardStatus(machine("suspect", state, now + 60_000, suspected = true), now)
            assertEquals(MachineBoardState.PICKUP, status.state)
            assertEquals("疑似未取", status.label)
            assertFalse(status.detail.orEmpty().contains("分钟"))
        }
        val unknown = machineBoardStatus(machine("missing", "unknown"), now)
        assertEquals(MachineBoardState.UNKNOWN, unknown.state)
        assertTrue(unknown.detail.orEmpty().contains("暂无法判断"))
    }
}
