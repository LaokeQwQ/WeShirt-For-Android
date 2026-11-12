@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Machine
import org.json.JSONObject

@Composable internal fun MachineFeedback(machine: Machine, state: AppState, vm: AppViewModel, session: MachineActionSession, login: () -> Unit) {
    var expanded by remember(machine.id) { mutableStateOf(false) }
    var type by remember(machine.id) { mutableStateOf("name") }
    var detail by remember(machine.id) { mutableStateOf("") }
    var confirmation by remember(machine.id) { mutableStateOf<MachineActionSnapshot?>(null) }
    var receipt by remember(machine.id) { mutableStateOf("") }
    OutlinedButton(onClick = { if (state.profile == null) login() else expanded = !expanded }, modifier = Modifier.fillMaxWidth(), enabled = !session.busy) { Text(if (expanded) "收起机器信息反馈" else "机器名称 / 位置 / 状态有误？") }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("name" to "名称", "location" to "机位", "state" to "状态", "other" to "其他").forEach { (value, label) ->
                FilterChip(type == value, { type = value }, { Text(label) }, enabled = !session.busy)
            }
        }
        OutlinedTextField(detail, { detail = it.take(300) }, label = { Text("具体情况") }, supportingText = { Text("${detail.length} / 300 · 交由管理员核实，不直接改变机器状态") }, minLines = 3, enabled = !session.busy, modifier = Modifier.fillMaxWidth())
        if (receipt.isNotBlank()) Text(receipt, color = MaterialTheme.colorScheme.primary)
        Button(onClick = { confirmation = MachineActionSnapshot(state.endpoint, state.profile?.id, state.building, machine) }, enabled = detail.isNotBlank() && !session.busy && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("提交反馈") }
    }
    confirmation?.let { target ->
        // Freeze editable content before the user confirms, while validating the same account and machine again at send time.
        val chosenType = remember(target) { type }
        val chosenDetail = remember(target) { detail.trim() }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("确认提交机器反馈") }, text = { Text("${target.machine.name}\n$chosenDetail") }, confirmButton = {
            TextButton(onClick = {
                confirmation = null; receipt = ""
                session.run(target) {
                    val result = request(target, "/api/machines/${vm.api.encoded(target.machine.id)}/feedback", JSONObject().put("type", chosenType).put("detail", chosenDetail))
                    if (current()) { receipt = if (result.optBoolean("duplicate")) "这条反馈已在处理中，无需重复提交。" else "反馈已提交，等待管理员核实。"; detail = "" }
                }
            }, enabled = !session.busy) { Text("确认提交") }
        }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
}
