package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.data.TaskRegistration

private data class ConfirmationMachine(val id: String, val name: String, val kind: String, val state: String, val finishAt: Long?, val estimated: Boolean)

@Composable
internal fun RegisterDialog(machine: Machine, busy: Boolean, error: String?, close: () -> Unit,
                            submit: (Int, String, Boolean, Boolean, Boolean, Boolean) -> Unit) {
    val washer = machine.kind != "dryer"
    var minutes by remember(machine.id, machine.kind) { mutableStateOf(if (washer) "30" else "40") }
    var program by remember(machine.id, machine.kind) { mutableStateOf(if (washer) "标准洗 30分钟" else "") }
    var note by remember(machine.id) { mutableStateOf("") }
    var disinfectant by remember(machine.id, machine.kind) { mutableStateOf(false) }
    var cleaned by remember(machine.id, machine.kind) { mutableStateOf(false) }
    var useMachine by remember(machine.id, machine.kind) { mutableStateOf(TaskRegistration.machineTimingAvailable(machine)) }
    var selfClean by remember(machine.id, machine.kind) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var confirmedMachine by remember { mutableStateOf<ConfirmationMachine?>(null) }
    var changedNotice by remember { mutableStateOf<String?>(null) }
    val target = ConfirmationMachine(machine.id, machine.name, machine.kind, machine.state, machine.finishAt, machine.estimated)
    LaunchedEffect(target) {
        if (confirm && confirmedMachine != target) {
            confirm = false
            changedNotice = "机器信息已更新，请重新核对后确认登记。"
        }
    }
    val valid = if (useMachine) TaskRegistration.machineTimingAvailable(machine) else TaskRegistration.validMinutes(machine.kind, minutes.toIntOrNull())
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (confirm) "确认登记" else "登记 · ${machine.name}") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (confirm) {
                    Text(machine.name, style = MaterialTheme.typography.titleMedium)
                    Text(when { useMachine -> "提醒方式：跟随机器剩余时间"; selfClean -> "提醒方式：桶自洁 · 6 分钟"; program.isNotBlank() -> "提醒方式：$program"; else -> "提醒方式：${minutes} 分钟（自定义）" })
                    Text("备注、消毒液和自清洁信息将公开显示。", style = MaterialTheme.typography.bodySmall)
                    Text("公开备注：${note.trim().ifBlank { "无" }}")
                    if (washer) {
                        Text("消毒液：${if (disinfectant) "使用" else "未使用"}")
                        Text("洗衣前自清洁：${if (cleaned) "已完成" else "未记录"}")
                    }
                    Text("登记仅用于计时和提醒，请以机器现场状态为准。", style = MaterialTheme.typography.bodySmall)
                } else {
                    if (TaskRegistration.machineTimingAvailable(machine) || useMachine) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(useMachine, { useMachine = it; if (it && selfClean) { selfClean = false; minutes = "30"; program = "标准洗 30分钟" } }, enabled = !busy)
                            Text("跟随设备剩余时间")
                        }
                    }
                    if (washer) {
                        Text("洗衣程序", style = MaterialTheme.typography.labelLarge)
                        TaskRegistration.washerPrograms.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { option ->
                                    FilterChip(selected = program == option.label && !useMachine, onClick = {
                                        minutes = option.minutes.toString(); program = option.label; selfClean = option.code == "self_clean"
                                    }, enabled = !busy && !useMachine, label = { Text(option.label) }, modifier = Modifier.weight(1f))
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30, 40, 60).forEach { value -> FilterChip(selected = minutes == value.toString() && !useMachine, onClick = { minutes = value.toString() }, enabled = !busy && !useMachine, label = { Text("$value 分钟") }) }
                        }
                    }
                    OutlinedTextField(minutes, { value -> if (value.length <= 3 && value.all(Char::isDigit)) { minutes = value; program = ""; selfClean = false } },
                        label = { Text(if (washer) "自定义时长（1–180 分钟）" else "烘干时长（10–180 分钟）") }, enabled = !busy && !useMachine,
                        isError = !useMachine && !valid, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text("公开备注（选填）") },
                        supportingText = { Text("${note.length}/200 · 由洗衣人填写，公开可见") }, enabled = !busy, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    if (washer) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(disinfectant, { disinfectant = it }, enabled = !busy); Text("使用消毒液") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cleaned, { cleaned = it }, enabled = !busy); Text("洗衣前已完成自清洁") }
                    }
                }
                if (changedNotice != null) Text(changedNotice!!, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { if (confirm) confirm = false else close() }) { Text(if (confirm) "返回修改" else "取消") } },
        confirmButton = { TextButton(enabled = !busy && valid, onClick = {
            if (confirm) {
                if (confirmedMachine == target) submit(minutes.toIntOrNull() ?: 30, note, disinfectant, cleaned, useMachine, selfClean)
                else { confirm = false; changedNotice = "机器信息已更新，请重新核对后确认登记。" }
            } else { confirmedMachine = target; changedNotice = null; confirm = true }
        }) { Text(if (busy) "正在登记…" else if (confirm) "确认登记" else "下一步") } }
    )
}
