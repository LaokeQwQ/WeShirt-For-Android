package com.laoke.weshirt.data

import com.laoke.weshirt.room.RoomMachine
import com.laoke.weshirt.room.RoomMachineState

data class WebRoomProjection(val machines: List<RoomMachine>, val hasBoundMachines: Boolean)

/** The web room uses explicit slot bindings, with only its documented mock IDs as a fallback. */
fun projectWebRoom(machines: List<Machine>, now: Long): WebRoomProjection {
    val slots = listOf("D1", "1", "2", "3", "4", "D2", "7", "6", "5")
    val bindings = machines.filter { it.slot.uppercase() in slots }.groupBy { it.slot.uppercase() }
    var bound = false
    val room = slots.map { slot ->
        val explicit = bindings[slot]
        // Ambiguous bindings must not make the same door open an arbitrary machine.
        val machine = if (explicit != null) explicit.singleOrNull() else {
            val mockId = if (slot.startsWith("D")) "1F-$slot" else "1F-0$slot"
            machines.singleOrNull { it.id == mockId && it.slot.isBlank() }
        }
        if (machine == null) RoomMachine("unbound:$slot", slot, "机位 $slot 尚未绑定", RoomMachineState.UNKNOWN,
            kind = if (slot.startsWith("D")) "dryer" else "washer")
        else {
            bound = true
            val state = when {
                machine.suspectedUncollected || machine.state in setOf("pending_pickup", "awaiting_pickup") -> RoomMachineState.AWAITING_PICKUP
                machine.state == "idle" -> RoomMachineState.IDLE
                machine.state == "occupied" -> if (machine.finishAt?.let { it - now in 1..180_000L } == true) RoomMachineState.FINISHING else RoomMachineState.RUNNING
                machine.state == "faulty" -> RoomMachineState.FAULT
                else -> RoomMachineState.UNKNOWN
            }
            RoomMachine(machine.id, slot, machine.name, state, machine.finishAt, machine.kind)
        }
    }
    return WebRoomProjection(room, bound)
}
