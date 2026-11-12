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
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

/** Keep the form within both the current viewing scope and operation scope. */
internal fun inspectionAllowed(access: JSONObject, building: String, operation: Boolean = false): Boolean {
    if (building.isBlank()) return false
    val permission = access.optJSONObject("permissions")?.optJSONObject("inspections") ?: return false
    if (!permission.optBoolean("view") || operation && !permission.optBoolean("operate")) return false
    fun includes(axis: String): Boolean {
        if (!permission.has(axis)) return false
        if (permission.isNull(axis)) return true
        val scope = permission.optJSONArray(axis) ?: return false
        return (0 until scope.length()).any { scope.optString(it) == building }
    }
    return includes("viewBuildings") && (!operation || includes("operateBuildings"))
}

@Composable
fun InspectionScreen(state: AppState, vm: AppViewModel, close: () -> Unit) {
    key(state.endpoint, state.profile?.id) { InspectionContent(state, vm, close) }
}

@Composable
private fun InspectionContent(state: AppState, vm: AppViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val account = state.endpoint + "|" + state.profile?.id
    fun current() = vm.state.value.let { it.endpoint + "|" + it.profile?.id == account && it.profile != null }
    var data by remember { mutableStateOf(JSONObject()) }
    var building by remember { mutableStateOf(state.building) }
    var issues by remember { mutableStateOf<Map<String, InspectionIssue>>(emptyMap()) }
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<JSONObject?>(null) }
    var confirmedMachines by remember { mutableStateOf<Set<String>>(emptySet()) }
    val buildings = data.optJSONArray("buildings")?.objects().orEmpty().filter { inspectionAllowed(state.access, it.str("code")) }
    val visibleBuilding = buildings.any { it.str("code") == building }
    val machines = data.optJSONArray("machines")?.objects().orEmpty().filter { visibleBuilding && it.str("dormBuilding") == building }
    val machineIds = machines.map { it.str("id") }.toSet()
    val history = data.optJSONArray("inspections")?.objects().orEmpty().filter { visibleBuilding && it.str("dormBuilding") == building }
    val canOperate = inspectionAllowed(state.access, building, operation = true)
    fun draft() = InspectionRound.body(building, machineIds, machines.mapNotNull { issues[it.str("id")] }, note, photo)
    suspend fun load() {
        val next = vm.api.call("/api/inspections")
        if (!current()) throw CancellationException("Account changed")
        data = next
        val allowed = next.optJSONArray("buildings")?.objects().orEmpty().filter { inspectionAllowed(vm.state.value.access, it.str("code")) }
        if (allowed.none { it.str("code") == building }) building = allowed.firstOrNull()?.str("code").orEmpty()
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
    LaunchedEffect(buildings.map { it.str("code") }) {
        preview = null
        if (buildings.none { it.str("code") == building }) building = buildings.firstOrNull()?.str("code").orEmpty()
    }
    LaunchedEffect(canOperate) { if (!canOperate) confirmation = null }
    LaunchedEffect(building) { issues = emptyMap(); photo = ""; note = ""; confirmation = null; feedback = null }
    val staleSelection = issues.keys.any { it !in machineIds }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text("整轮巡检") }, navigationIcon = { IconButton(onClick = close, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭") } }, actions = { TextButton(onClick = { action { load() } }, enabled = !busy) { Text("刷新") } }) }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("inspection-round"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text(data.optJSONObject("identity")?.str("label").orEmpty(), style = MaterialTheme.typography.labelLarge) }
                    item { ChoiceRow("巡检楼栋", buildings.map { it.str("code") to it.str("name") }, building, !busy) { building = it } }
                    item { Text(if (canOperate) "一次检查本楼全部 ${machines.size} 台机器，仅勾选异常机器。没有勾选时，本轮会登记为全部正常。" else "当前楼栋为只读巡检权限，可查看机器清单和下方的巡检记录。", style = MaterialTheme.typography.bodyMedium) }
                    item {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("洗衣房全景图（选填）", style = MaterialTheme.typography.titleSmall)
                            Text("可拍摄整个洗衣房或选择已有全景照片，保持图片原比例。", style = MaterialTheme.typography.bodySmall)
                            NativeAttachmentActions(!busy && canOperate, "$account|$building", onMedia = { uri, cleanup ->
                                val target = building
                                action {
                                    try {
                                        val image = withContext(Dispatchers.IO) { compressPhoto(context, uri, 420_000, 2000) }
                                        if (!current() || target != building) throw CancellationException("Inspection changed")
                                        photo = "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)
                                    } finally { cleanup() }
                                }
                            }, onError = { error = it }, imagesOnly = true)
                            if (photo.isNotBlank()) {
                                AsyncImage(photo, "本轮全景照片预览", Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit)
                                TextButton(onClick = { photo = "" }, enabled = !busy && canOperate) { Text("移除全景图") }
                            } else Text("未添加图片，可直接提交", style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                    item { Text("异常机器 · 已勾选 ${issues.size} 台", style = MaterialTheme.typography.titleMedium) }
                    if (machines.isEmpty()) item { Text("本楼暂无可巡检机器，请刷新或选择其他楼栋。") }
                    items(machines, key = { it.str("id") }) { machine ->
                        val id = machine.str("id")
                        InspectionMachineRow(machine.str("name"), issues[id], !busy && canOperate, { checked -> issues = if (checked) issues + (id to InspectionIssue(id)) else issues - id }, { value -> issues = issues + (id to value) })
                    }
                    if (staleSelection) item { Text("机器清单已变化，请重新选择楼栋并核对本轮异常。", color = MaterialTheme.colorScheme.error) }
                    item { OutlinedTextField(note, { note = it.take(500) }, label = { Text("本轮备注（选填）") }, enabled = !busy && canOperate, modifier = Modifier.fillMaxWidth(), minLines = 2, supportingText = { Text("${note.length}/500") }) }
                    item { Button(onClick = { try { confirmation = draft(); confirmedMachines = machineIds } catch (problem: Exception) { error = clientFailure(problem, "请核对巡检内容") } }, enabled = !busy && canOperate && !staleSelection && machineIds.isNotEmpty() && issues.values.all { it.reasons.isNotEmpty() }, modifier = Modifier.fillMaxWidth().testTag("inspection-submit")) { Text(if (busy) "正在处理…" else if (!canOperate) "当前楼栋无上报权限" else if (issues.isEmpty()) "提交本轮 · 全部正常" else "提交本轮 · ${issues.size} 台异常") } }
                    if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    if (feedback != null) item { Text(feedback!!, color = MaterialTheme.colorScheme.primary) }
                    item { HorizontalDivider(); Text("本楼巡检记录", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge) }
                    if (history.isEmpty()) item { Text("暂无巡检记录") }
                    items(history, key = { it.str("id") }) { entry -> InspectionHistoryCard(entry, !busy) { action { val result = vm.api.call("/api/inspections/${vm.api.encoded(entry.str("id"))}/photo"); if (current()) preview = result.str("photoData") } } }
                }
            }
        }
    }
    confirmation?.let { frozen ->
        val count = frozen.optJSONArray("issues")?.length() ?: 0
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text("确认提交整轮巡检") }, text = { Column(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${buildings.find { it.str("code") == frozen.str("dormBuilding") }?.str("name").orEmpty()} · 共 ${machines.size} 台机器")
            Text(if (count == 0) "没有勾选异常机器，将记录为全部正常。" else "本轮 $count 台异常，其余机器正常；异常摘要会尝试通知本楼指定群。")
            Text(if (frozen.str("photoData").isBlank()) "未添加全景图" else "已添加全景图")
        } }, confirmButton = { TextButton(enabled = !busy && canOperate, onClick = {
            action {
                if (!inspectionAllowed(vm.state.value.access, frozen.str("dormBuilding"), operation = true)) throw ApiException(403, "permission_changed", "client_${UUID.randomUUID()}", "巡检操作权限已变化，请刷新后重试")
                if (staleSelection || confirmedMachines != machineIds || frozen.toString() != draft().toString()) throw ApiException(409, "form_changed", "client_${UUID.randomUUID()}", "巡检内容已变化，请重新确认")
                val result = vm.api.call("/api/inspections", "POST", frozen)
                if (!current()) throw CancellationException("Account changed")
                issues = emptyMap(); note = ""; photo = ""; confirmation = null
                feedback = inspectionDelivery(result.optJSONObject("inspection")?.optJSONObject("notification"))
                load()
            }
        }) { Text("确认上报") } }, dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text("返回修改") } })
    }
    preview?.let { image -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("巡检全景图") }, text = { AsyncImage(image, "巡检全景图", Modifier.fillMaxWidth().heightIn(max = 440.dp), contentScale = ContentScale.Fit) }, confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }) }
}

@Composable
internal fun InspectionMachineRow(name: String, issue: InspectionIssue?, enabled: Boolean, select: (Boolean) -> Unit, update: (InspectionIssue) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(issue != null, select, enabled = enabled, modifier = Modifier.testTag("inspection-machine-select")); Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(if (issue == null) "正常" else "异常", color = if (issue == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
        if (issue != null) {
            Text("勾选具体情况，可多选", style = MaterialTheme.typography.labelMedium)
            InspectionRound.reasons.forEach { (code, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(code in issue.reasons, { selected -> update(issue.copy(reasons = if (selected) issue.reasons + code else issue.reasons - code)) }, enabled = enabled, modifier = Modifier.testTag("inspection-reason:$code")); Text(label, Modifier.weight(1f)) }
            }
            OutlinedTextField(issue.note, { update(issue.copy(note = it.take(500))) }, label = { Text("异常说明（选填）") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
    } }
}

@Composable
private fun InspectionHistoryCard(entry: JSONObject, enabled: Boolean, photo: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val round = entry.str("type") == "round"
        Text(if (round) "整轮巡检 · ${entry.optInt("inspectedMachineCount")} 台" else entry.str("machineName"), style = MaterialTheme.typography.titleMedium)
        Text(if (entry.str("status") == "issue") if (round) "${entry.optInt("issueCount")} 台异常" else "异常" else if (round) "全部正常" else "正常", color = if (entry.str("status") == "issue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        entry.optJSONArray("issues")?.objects().orEmpty().forEach { issue ->
            val codes = issue.optJSONArray("reasons")
            Text(issue.str("machineName", issue.str("machineId")) + " · " + (0 until (codes?.length() ?: 0)).joinToString("、") { InspectionRound.reasons[codes?.optString(it)] ?: "其他异常" })
            if (issue.str("note").isNotBlank()) Text(issue.str("note"), style = MaterialTheme.typography.bodySmall)
        }
        if (entry.str("note").isNotBlank()) Text(entry.str("note"))
        Text(entry.str("at") + " · " + entry.optJSONObject("inspector")?.str("nick").orEmpty(), style = MaterialTheme.typography.labelSmall)
        Text(inspectionDelivery(entry.optJSONObject("notification")), style = MaterialTheme.typography.bodySmall)
        if (entry.optBoolean("hasPhoto")) TextButton(onClick = photo, enabled = enabled) { Text(if (round) "查看全景图" else "查看现场照片") }
    } }
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
    "not_required" -> "本轮全部正常，巡检已保存，无需群通知"
    "sent" -> "巡检已保存，本楼群通知已送达"
    "sending" -> "巡检已保存，正在通知本楼群"
    "failed" -> "巡检已保存，群通知未确认送达，后台已记录"
    "cooldown" -> "巡检已保存，相同异常短时间内不重复通知"
    "unconfigured" -> "巡检已保存，本楼尚未指定通知群"
    else -> "巡检已保存"
}
