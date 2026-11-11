package com.laoke.weshirt.data

import com.laoke.weshirt.room.RoomMachineState
import org.junit.Assert.*
import org.junit.Test

class WebRoomProjectionTest {
    private fun machine(id: String, slot: String = "", state: String = "idle", end: Long? = null) =
        Machine(id, "机器", slot, if (slot.startsWith("D")) "dryer" else "washer", state, end, "", false, false, "")

    @Test fun explicitBindingsKeepAllNinePhysicalSlotsAndIgnoreUnmappedFloors() {
        val projection = projectWebRoom(listOf(machine("real-dryer", "D1"), machine("upper-floor")), 1_000)
        assertTrue(projection.hasBoundMachines)
        assertEquals(9, projection.machines.size)
        assertEquals("real-dryer", projection.machines.first { it.slot == "D1" }.id)
        assertFalse(projection.machines.any { it.id == "upper-floor" })
        assertEquals(8, projection.machines.count { it.state == RoomMachineState.UNKNOWN })
    }

    @Test fun unconfiguredOrAmbiguousBindingsUseTheTwoDimensionalBoard() {
        assertFalse(projectWebRoom(listOf(machine("arbitrary-1")), 0).hasBoundMachines)
        assertFalse(projectWebRoom(listOf(machine("first", "1"), machine("second", "1")), 0).hasBoundMachines)
    }

    @Test fun onlyDocumentedMockIdsCanFillUnboundSlotsAndElapsedTimeDoesNotBecomeIdle() {
        val projection = projectWebRoom(listOf(machine("1F-01", state = "occupied", end = 500), machine("other-id-2")), 1_000)
        assertTrue(projection.hasBoundMachines)
        assertEquals(RoomMachineState.RUNNING, projection.machines.first { it.slot == "1" }.state)
        assertEquals("unbound:2", projection.machines.first { it.slot == "2" }.id)
    }
}
