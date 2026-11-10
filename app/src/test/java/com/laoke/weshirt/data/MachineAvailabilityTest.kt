package com.laoke.weshirt.data

import org.junit.Assert.*
import org.junit.Test

class MachineAvailabilityTest {
    private fun machine(state: String, end: Long? = null, uncollected: Boolean = false) =
        Machine("test", "测试机器", "1", "washer", state, end, "", false, false, "", suspectedUncollected = uncollected)

    @Test fun elapsedEstimateStillDoesNotMakeARunningMachineAvailable() {
        val now = 1_000_000L
        assertTrue(machine("occupied", now - 1).availability(now) > machine("idle").availability(now))
        assertEquals("使用中", machine("occupied", now - 1).stateLabel)
    }

    @Test fun suspectedClothesAreNotAdvertisedAsAnEmptyAvailableMachine() {
        val now = 1_000_000L
        val withClothes = machine("idle", uncollected = true)
        assertEquals("疑似未取", withClothes.stateLabel)
        assertTrue(withClothes.availability(now) > machine("occupied", now + 60_000).availability(now))
        assertEquals("idle", withClothes.state)
    }
}
