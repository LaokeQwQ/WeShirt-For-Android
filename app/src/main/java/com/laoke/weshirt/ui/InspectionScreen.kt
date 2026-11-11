@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.laoke.weshirt.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

internal fun clientFailure(problem: Exception, fallback: String): String =
    if (problem is ApiException) problem.message.orEmpty()
    else "$fallback\n状态码 0 · 事件 client_${UUID.randomUUID().toString().replace("-", "")}"

/** Account-scoped form. A failed submission retains the photo and note. */
@Composable
fun InspectionScreen(state: AppState, vm: AppViewModel, close: () -> Unit) {
    val account = state.endpoint + "|" + state.profile?.id
    key(account) { InspectionContent(state, vm, close) }
}

@Composable
private fun InspectionContent(state: AppState, vm: AppViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val account = state.endpoint + "|" + state.profile?.id
    fun current() = vm.state.value.let { it.endpoint + "|" + it.profile?.id == account && it.profile != null }
    var data by remember { mutableStateOf(JSONObject()) }
    var building by remember { mutableStateOf(state.building) }
    var machineId by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<JSONObject?>(null) }
    val buildings = data.optJSONArray("buildings")?.objects().orEmpty()
    val machines = data.optJSONArray("machines")?.objects().orEmpty().filter { it.str("dormBuilding") == building }
    val history = data.optJSONArray("inspections")?.objects().orEmpty().filter { it.str("dormBuilding") == building }
    suspend fun load() {
        val next = vm.api.call("/api/inspections")
        if (!current()) throw CancellationException("Account changed")
        data = next
        if (next.optJSONArray("buildings")?.objects().orEmpty().none { it.str("code") == building }) building = next.optJSONArray("buildings")?.optJSONObject(0)?.str("code").orEmpty()
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        coroutine.launch {
            try { if (!current()) throw CancellationException("Account changed"); block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (problem: Exception) { if (current()) error = clientFailure(problem, "巡检操作未完成，请重试") }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { action { load() } }
    LaunchedEffect(building) { machineId = ""; photo = ""; note = ""; issue = false; confirmation = null }
    LaunchedEffect(machineId) { photo = ""; confirmation = null }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text("楼栋巡检") }, navigationIcon = { IconButton(onClick = close, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭") } }, actions = { TextButton(onClick = { action { load() } }, enabled = !busy) { Text("刷新") } }) }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text(data.optJSONObject("identity")?.str("label").orEmpty(), style = MaterialTheme.typography.labelLarge) }
                    item { ChoiceRow("巡检楼栋", buildings.map { it.str("code") to it.str("name") }, building, !busy) { building = it } }
                    item { ChoiceRow("机器", machines.map { it.str("id") to it.str("name") }, machineId, !busy) { machineId = it } }
                    item { Row { FilterChip(!issue, { issue = false }, label = { Text("正常") }, enabled = !busy); Spacer(Modifier.width(12.dp)); FilterChip(issue, { issue = true }, label = { Text("发现异常") }, enabled = !busy) } }
                    item { OutlinedTextField(note, { note = it.take(500) }, label = { Text("现场情况与处理建议") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 3, supportingText = { Text("${note.length}/500") }) }
                    item {
                        Row { NativeAttachmentActions(!busy && machineId.isNotBlank(), "$account|$building|$machineId", onMedia = { uri, cleanup ->
                            val target = building
                            val targetMachine = machineId
                            action {
                                try {
                                    val image = withContext(Dispatchers.IO) { prepareMedia(context, uri) }
                                    if (!current() || target != building || targetMachine != machineId) throw CancellationException("Inspection changed")
                                    require(image.second.startsWith("image/"))
                                    photo = "data:${image.second};base64," + Base64.encodeToString(image.first, Base64.NO_WRAP)
                                } finally { cleanup() }
                            }
                        }, onError = { error = it }, imagesOnly = true); Text(if (photo.isBlank()) "添加现场照片（必填）" else "已添加现场照片") }
                        if (photo.isNotBlank()) AsyncImage(photo, "巡检照片", Modifier.fillMaxWidth().height(180.dp))
                    }
                    item { Button(onClick = { confirmation = JSONObject().put("machineId", machineId).put("dorm",building).put("status", if (issue) "issue" else "normal").put("note", note.trim()).put("photoData", photo) }, enabled = !busy && machines.any { it.str("id") == machineId } && note.isNotBlank() && photo.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在处理…" else "提交巡检") } }
                    if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    if (feedback != null) item { Text(feedback!!, color = MaterialTheme.colorScheme.primary) }
                    item { Text("本楼巡检记录", style = MaterialTheme.typography.titleLarge) }
                    if (history.isEmpty()) item { Text("暂无巡检记录") }
                    items(history, key = { it.str("id") }) { entry -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.str("machineName") + if (entry.str("status") == "issue") " · 异常" else " · 正常", style = MaterialTheme.typography.titleMedium)
                        Text(entry.str("note")); Text(entry.str("at") + " · " + entry.optJSONObject("inspector")?.str("nick").orEmpty(), style = MaterialTheme.typography.labelSmall)
                        Text(inspectionDelivery(entry.optJSONObject("notification")), style = MaterialTheme.typography.bodySmall)
                        if (entry.optBoolean("hasPhoto")) TextButton(onClick = { action { val result = vm.api.call("/api/inspections/${vm.api.encoded(entry.str("id"))}/photo"); if (current()) preview = result.str("photoData") } }, enabled = !busy) { Text("查看现场照片") }
                    } } }
                }
            }
        }
    }
    confirmation?.let { frozen -> AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text("确认提交巡检") }, text = { Text("${machines.find { it.str("id") == frozen.str("machineId") }?.str("name")}\n${if (issue) "发现异常，可能通知本楼指定群" else "现场正常"}\n${frozen.str("note")}\n已选择一张照片") }, confirmButton = { TextButton(enabled = !busy, onClick = {
        action {
            if (frozen.str("machineId") != machineId || frozen.str("dorm") != building || machines.none{it.str("id")==machineId} || frozen.str("note") != note.trim() || frozen.str("photoData") != photo || frozen.str("status") != if (issue) "issue" else "normal") throw ApiException(409, "form_changed", "client_" + UUID.randomUUID().toString().replace("-", ""), "巡检内容已变化，请重新确认")
            val result = vm.api.call("/api/inspections", "POST", frozen)
            if (!current()) throw CancellationException("Account changed")
            note = ""; photo = ""; confirmation = null
            feedback = inspectionDelivery(result.optJSONObject("inspection")?.optJSONObject("notification"))
            load()
        }
    }) { Text("确认上报") } }, dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text("返回修改") } }) }
    preview?.let { image -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("现场照片") }, text = { AsyncImage(image, "巡检照片", Modifier.fillMaxWidth().heightIn(max = 440.dp)) }, confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }) }
}

@Composable
internal fun ChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, enabled: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box { OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(options.find { it.first == selected }?.second ?: "请选择") }
            DropdownMenu(expanded, { expanded = false }) { options.forEach { (value, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; choose(value) }) } }
        }
    }
}

private fun inspectionDelivery(value: JSONObject?) = when (value?.str("status")) {
    "not_required" -> "正常巡检已保存，无需群通知"
    "sent" -> "巡检已保存，本楼群通知已送达"
    "sending" -> "巡检已保存，正在通知本楼群"
    "failed" -> "巡检已保存，群通知未确认送达，后台已记录"
    "cooldown" -> "巡检已保存，同台异常短时间内不重复通知"
    "unconfigured" -> "巡检已保存，本楼尚未指定通知群"
    else -> "巡检已保存"
}
