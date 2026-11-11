package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import com.laoke.weshirt.ui.assistant.AssistantDialog
import com.laoke.weshirt.ui.social.SocialDialog
import com.laoke.weshirt.ui.social.QueueNoticeDialog
import com.laoke.weshirt.ui.user.*
import kotlinx.coroutines.delay
import org.json.JSONObject

private data class TaskConfirmation(val task: LaundryTask, val delete: Boolean)

@Composable fun LaundryScreen(state: AppState, vm: AppViewModel, login: () -> Unit) {
    if (state.profile == null) {
        EmptyPanel("你的洗衣，一目了然", "登录后查看登记、排队和好友代取。", Icons.Outlined.LocalLaundryService, "钉钉登录", login)
        return
    }
    key(state.endpoint, state.profile.id) { SignedInLaundryScreen(state, vm) }
}

@Composable private fun SignedInLaundryScreen(state: AppState, vm: AppViewModel) {
    val session = rememberUserFeatureSession(state, vm)
    var social by remember { mutableStateOf(false) }
    var assistant by remember { mutableStateOf(false) }
    var inspections by remember { mutableStateOf(false) }
    var queueNotices by remember { mutableStateOf(false) }
    var pickupTaskId by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var registerId by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<TaskConfirmation?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var taskDetails by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    val busy = session.busy || state.busy
    val permissions = state.access.optJSONObject("permissions")
    val tasksView = permissions?.optJSONObject("tasks")?.optBoolean("view", true) != false
    val tasksOperate = permissions?.optJSONObject("tasks")?.optBoolean("operate", true) != false
    val socialView = permissions?.optJSONObject("social")?.optBoolean("view", true) != false
    val assistantView = permissions?.optJSONObject("assistant")?.optBoolean("view", true) != false
    val role = state.access.optJSONObject("identity")?.str("role", state.profile?.role ?: "user") ?: state.profile?.role.orEmpty()
    val inspectionView = permissions?.optJSONObject("inspections")?.optBoolean("view", role in setOf("super", "admin", "volunteer")) ?: (role in setOf("super", "admin", "volunteer"))
    fun loadDetails() {
        if (tasksView) session.run(silent = true) {
            taskDetails = request("/api/tasks").optJSONArray("tasks")?.objects().orEmpty().associateBy { it.str("id") }
        }
    }
    LaunchedEffect(session, state.tasks) { loadDetails() }
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { now = System.currentTimeMillis(); delay(30_000) } } }
    val active = state.tasks.filterNot { it.done }
    val done = state.tasks.filter { it.done }.sortedByDescending { it.startAt ?: 0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (socialView) FilledTonalButton(onClick = { social = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("朋友与代取") }
                if (assistantView) FilledTonalButton(onClick = { assistant = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("洗衣助手") }
            }
        }
        item { FeatureError(session) }
        if (permissions?.optJSONObject("queue")?.optBoolean("view", true) != false) item {
            OutlinedButton(onClick = { queueNotices = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("预约提醒收件箱") }
        }
        if (tasksView) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("正在洗衣", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { picker = true }, enabled = !busy && tasksOperate && state.machines.isNotEmpty()) { Text("登记洗衣") }
                }
            }
            if (active.isEmpty()) item { Text("还没有进行中的登记，选一台机器开始吧。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(active, key = { it.id }) { task ->
                val raw = taskDetails[task.id]
                val finished = task.endAt?.let { it <= now } == true || raw?.str("takeState") == "pending"
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(laundryTimeLabel(task, now), style = MaterialTheme.typography.titleLarge, color = if (finished) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    TaskDetailText(task, raw)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (finished) Button(onClick = { confirm = TaskConfirmation(task, false) }, enabled = !busy && tasksOperate) { Text("我已取衣") }
                        if (socialView) OutlinedButton(onClick = { pickupTaskId = task.id }, enabled = !busy) { Text("请 TA 代取") }
                    }
                    TextButton(onClick = { confirm = TaskConfirmation(task, true) }, enabled = !busy && tasksOperate) { Text("删除登记", color = MaterialTheme.colorScheme.error) }
                } }
            }
        }
        if (state.queues.isNotEmpty()) item { Text("洗衣排队", style = MaterialTheme.typography.titleLarge) }
        items(state.queues, key = { "queue." + it.str("id", it.str("machineId")) }) { queue ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.machines.find { it.id == queue.str("machineId") }?.name ?: "机器 ${queue.str("machineId")}", style = MaterialTheme.typography.titleMedium)
                Text("${if (queue.optBoolean("mine")) "我的" else queue.str("nick", "同学") + "的"}排位 · 第 ${queue.optInt("position")} 位 · 预计使用 ${queue.optInt("durationMin")} 分钟", style = MaterialTheme.typography.bodySmall)
                val start = epoch(queue.str("startAt"))
                Text(if (start == null) "预计开始时间以机器剩余时间为准" else if (start > now) "预计 ${((start - now + 59_999) / 60_000)} 分钟后可用" else "预计等待时间已到，请核对机器现场状态")
                if (queue.optBoolean("uncertain")) Text("设备结束时间暂不明确，已按完整周期保守估算。", style = MaterialTheme.typography.bodySmall)
                Text("排到队首后，机器结束前 3 分钟及结束时会提醒；到场后请核对设备和衣物。", style = MaterialTheme.typography.bodySmall)
                if (queue.optBoolean("mine")) TextButton(onClick = {
                    session.run(refresh = true) { request("/api/queue?machineId=${vm.api.encoded(queue.str("machineId"))}", "DELETE"); vm.message("已取消排队") }
                }, enabled = !busy && permissions?.optJSONObject("queue")?.optBoolean("operate", true) != false) { Text("取消排队") }
            } }
        }
        val received = state.social.optJSONArray("receivedPickups")?.objects().orEmpty().filter { it.str("state") in setOf("pending", "accepted") }
        if (socialView && received.isNotEmpty()) item {
            Card(onClick = { social = true }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("朋友请我代取 · ${received.size} 条", style = MaterialTheme.typography.titleMedium)
                received.forEach { request -> Text(request.optJSONObject("task")?.str("machineName", "朋友的洗衣任务").orEmpty() + " · " + if (request.str("state") == "pending") "待确认" else "已接受") }
                Text("查看剩余时间并处理请求", color = MaterialTheme.colorScheme.primary)
            } }
        }
        if (tasksView) {
            item { TextButton(onClick = { history = !history }, modifier = Modifier.fillMaxWidth()) { Text(if (history) "收起洗衣历史（${done.size}）" else "查看洗衣历史（${done.size}）") } }
            if (history) {
                item { OutlinedTextField(historyQuery, { historyQuery = it.take(100) }, label = { Text("按机器名称或备注搜索") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                val filtered = done.filter { historyQuery.isBlank() || it.name.contains(historyQuery, true) || it.note.contains(historyQuery, true) }
                if (filtered.isEmpty()) item { Text(if (done.isEmpty()) "暂时没有已完成的登记。" else "没有匹配的洗衣记录。") }
                items(filtered, key = { "done.${it.id}" }) { task -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text("已确认取衣", color = MaterialTheme.colorScheme.primary)
                    task.startAt?.let { Text(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), style = MaterialTheme.typography.bodySmall) }
                    TaskDetailText(task, taskDetails[task.id])
                    TextButton(onClick = { confirm = TaskConfirmation(task, true) }, enabled = !busy && tasksOperate) { Text("删除记录", color = MaterialTheme.colorScheme.error) }
                } } }
            }
        }
        if (inspectionView) item {
            Card(onClick = { inspections = true }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FactCheck, null)
                Column { Text("楼栋巡检", style = MaterialTheme.typography.titleMedium); Text("检查机器、拍照备注并上报情况", style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("选择机器") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { items(state.machines.sortedBy { it.availability(now) }, key = { it.id }) { machine -> TextButton(onClick = { registerId = machine.id; picker = false; session.error = null }, modifier = Modifier.fillMaxWidth()) { Text("${machine.name} · ${machine.stateLabel}") } } }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text("返回") } })
    registerId?.let { id ->
        val machine = state.machines.find { it.id == id }
        if (machine != null) RegisterDialog(machine, busy, session.error, { registerId = null }) { minutes, note, disinfectant, cleaned, useMachine, selfClean ->
            session.run(refresh = true) {
                val currentMachine = vm.state.value.machines.find { it.id == machine.id } ?: throw IllegalStateException("机器信息已变化，请重新选择")
                if (currentMachine != machine) throw IllegalStateException("机器信息已变化，请重新核对并确认登记")
                request("/api/tasks", "POST", TaskRegistration.body(currentMachine, minutes, note, disinfectant, cleaned, useMachine, selfClean))
                registerId = null; vm.message("登记成功")
            }
        }
    }
    confirm?.let { ask -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (ask.delete) "删除登记？" else "确认已取衣？") }, text = { Text(ask.task.name + if (ask.delete) "\n删除这条记录并释放登记占用，机器远端状态不会随登记删除改变。" else "\n请确认已经取走自己的衣物。确认后会结束这条登记并释放登记占用。") }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            session.run(refresh = true) {
                val currentTask = vm.state.value.tasks.find { it.id == ask.task.id } ?: throw IllegalStateException("登记状态已变化，请刷新后重试")
                if (!ask.delete && currentTask.done) throw IllegalStateException("此登记已经确认取衣，请刷新查看")
                request("/api/tasks/${vm.api.encoded(ask.task.id)}" + if (ask.delete) "" else "/take", if (ask.delete) "DELETE" else "POST")
                confirm = null; vm.message(if (ask.delete) "登记已删除" else "已确认取衣")
            }
        }) { Text(if (ask.delete) "确认删除" else "已取走衣物") }
    }) }
    if (social || pickupTaskId != null) SocialDialog(state, vm, { social = false; pickupTaskId = null }, pickupTaskId)
    if (assistant) AssistantDialog(state, vm) { assistant = false }
    if (inspections) InspectionScreen(state, vm) { inspections = false }
    if (queueNotices) QueueNoticeDialog(state, vm) { queueNotices = false }
}

@Composable private fun TaskDetailText(task: LaundryTask, raw: JSONObject?) {
    raw?.let {
        Text(when {
            it.str("mode") == "machine" -> if (task.estimated) "按估算时间提醒，请核对现场状态" else "按机器时间提醒 · 程序未提供"
            it.str("program") == "self_clean" -> "桶自洁 · 6 分钟提醒"
            else -> "自行设置 ${it.optInt("minutes")} 分钟提醒，不代表机器远端状态"
        }, style = MaterialTheme.typography.bodySmall)
    }
    if (task.note.isNotBlank()) Text("公开备注：${task.note}")
    if (task.kind != "dryer") Text("消毒液：${if (task.disinfectant) "使用" else "未使用"} · 自清洁：${if (task.selfCleaned) "已记录" else "未记录"}", style = MaterialTheme.typography.bodySmall)
}

internal fun laundryTimeLabel(task: LaundryTask, now: Long): String {
    if (task.done) return "已确认取衣"
    val end = task.endAt ?: return "结束时间待确认"
    if (end > now) return "登记计时剩余 ${((end - now + 59_999) / 60_000).coerceAtLeast(1)} 分钟"
    val overdue = ((now - end) / 60_000).coerceAtLeast(0)
    return if (overdue == 0L) "登记计时已到 · 请现场确认" else "登记计时结束${when { overdue < 60 -> "$overdue 分钟"; overdue < 1440 -> "${overdue / 60} 小时"; else -> "${overdue / 1440} 天" }} · 请确认机器和衣物"
}
