package com.laoke.weshirt.ui

import com.laoke.weshirt.data.LaundryTask
import com.laoke.weshirt.data.Machine
import org.junit.Assert.*
import org.junit.Test

class BoardAndCollectionTest {
    private fun machine(id: String, state: String, uncollected: Boolean = false) = Machine(id, id, id, "washer", state, null, "", false, false, "", suspectedUncollected = uncollected)
    private fun task(id: String, end: Long?, done: Boolean = false) = LaundryTask(id, "m", "洗衣机", "washer", end, done, "", false, false, false)

    @Test fun boardCountsDoNotOfferSuspectedUncollectedAsAvailable() {
        val result = machineBoardSummary(listOf(machine("1", "idle"), machine("2", "idle", true), machine("3", "abnormal"), machine("4", "occupied"), machine("5", "faulty"), machine("6", "unknown")))
        assertEquals(MachineBoardSummary(6, 1, 3, 1), result)
    }

    @Test fun staleDataUsesServerTimestampAndDoesNotTurnNegativeOnClockSkew() {
        assertNull(machineBoardStaleMinutes(null, 500_000))
        assertEquals(3L, machineBoardStaleMinutes(200_000, 380_000))
        assertEquals(0L, machineBoardStaleMinutes(500_000, 400_000))
    }

    @Test fun openingPromptOnlySelectsAnUncollectedTaskWithinTheThreeHourWindow() {
        val now = 20_000_000L
        val tasks = listOf(task("recent", now - 120_000), task("older", now - 4_800_000), task("too-old", now - 181 * 60_000), task("future", now + 60_000), task("done", now - 60_000, true), task("no-time", null), task("just-expired", now - 59_000))
        assertEquals("recent", overdueCollectionCandidate(tasks, now)?.id)
        assertNull(overdueCollectionCandidate(tasks.filter { it.id !in setOf("recent", "older") }, now))
        assertEquals("boundary", overdueCollectionCandidate(listOf(task("boundary", now - 180 * 60_000)), now)?.id)
    }
}
