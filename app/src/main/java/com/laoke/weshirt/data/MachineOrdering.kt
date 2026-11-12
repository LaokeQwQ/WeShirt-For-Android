package com.laoke.weshirt.data

import java.text.Collator
import java.util.Locale

/** Matches the status groups in the web board's machineOrder.js. Never infers idle from an estimate. */
fun sortMachinesByAvailability(machines: List<Machine>, now: Long): List<Machine> {
    val names = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    return machines.sortedWith { left, right ->
        val group = availabilityGroup(left, now).compareTo(availabilityGroup(right, now))
        if (group != 0) group
        else {
            val end = if (availabilityGroup(left, now) == 1) {
                requireNotNull(left.finishAt).compareTo(requireNotNull(right.finishAt))
            } else 0
            if (end != 0) end else naturalNameCompare(displayName(left), displayName(right), names)
        }
    }
}

private fun availabilityGroup(machine: Machine, now: Long): Int = when {
    machine.state == "idle" && !machine.suspectedUncollected -> 0
    machine.state == "occupied" && machine.finishAt?.let { it > now } == true -> 1
    machine.suspectedUncollected || machine.state == "occupied" || machine.state == "abnormal" -> 2
    machine.state == "faulty" -> 4
    else -> 3
}

private fun displayName(machine: Machine) = machine.name.ifBlank { machine.id }

// Compare digit runs without parsing to an integer: names may contain long or zero-padded IDs.
private fun naturalNameCompare(left: String, right: String, collator: Collator): Int {
    var l = 0
    var r = 0
    while (l < left.length && r < right.length) {
        val leftNumeric = left[l] in '0'..'9'
        val rightNumeric = right[r] in '0'..'9'
        var le = l + 1
        var re = r + 1
        while (le < left.length && (left[le] in '0'..'9') == leftNumeric) le++
        while (re < right.length && (right[re] in '0'..'9') == rightNumeric) re++
        val result = if (leftNumeric && rightNumeric) {
            val a = left.substring(l, le).trimStart('0').ifEmpty { "0" }
            val b = right.substring(r, re).trimStart('0').ifEmpty { "0" }
            a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
        } else collator.compare(left.substring(l, le), right.substring(r, re))
        if (result != 0) return result
        l = le
        r = re
    }
    return (left.length - l).compareTo(right.length - r)
}

enum class MachineBoardState { AVAILABLE, RUNNING, PICKUP, REVIEW, FAULT, UNKNOWN }

data class MachineBoardStatus(val state: MachineBoardState, val label: String, val detail: String?)

/** Only an explicitly running machine can expose a remaining-time estimate. */
fun machineBoardStatus(machine: Machine, now: Long): MachineBoardStatus = when {
    machine.suspectedUncollected || machine.state == "pending_pickup" || machine.state == "awaiting_pickup" ->
        MachineBoardStatus(MachineBoardState.PICKUP, "疑似未取", "请先确认现场衣物")
    machine.state == "idle" -> MachineBoardStatus(MachineBoardState.AVAILABLE, "空闲", null)
    machine.state == "occupied" -> {
        val end = machine.finishAt
        val detail = when {
            end == null -> "剩余时间待同步"
            end <= now -> "即将完成，等待设备确认"
            else -> "预计剩余 ${(end - now - 1) / 60_000 + 1} 分钟"
        }
        MachineBoardStatus(MachineBoardState.RUNNING, "使用中", detail)
    }
    machine.state == "abnormal" -> MachineBoardStatus(MachineBoardState.REVIEW, "待复核", "设备状态待复核")
    machine.state == "faulty" -> MachineBoardStatus(MachineBoardState.FAULT, "暂不可用", "请使用其他机器")
    else -> MachineBoardStatus(MachineBoardState.UNKNOWN, "状态待更新", "暂无法判断是否可用")
}
