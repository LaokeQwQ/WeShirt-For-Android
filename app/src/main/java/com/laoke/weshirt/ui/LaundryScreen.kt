@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
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
    val role = state.access.optJSONObject("identity")?.str("role", state.profile?.role ?: "user") ?: state.profile?.role.orEmpty()
    val inspectionView = permissions?.optJSONObject("inspections")?.optBoolean("view", role in setOf("super", "admin", "volunteer")) ?: (role in setOf("super", "admin", "volunteer"))
    fun loadDetails() {
        if (tasksView) session.run(silent = true) {
            taskDetails = request("/api/tasks").optJSONArray("tasks")?.objects().orEmpty().associateBy { it.str("id") }
        }
    }
    LaunchedEffect(session, state.tasks) { loadDetails() }
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { now = System.currentTimeMillis(); delay(30_000) } } }
    val records = laundryRecords(state.tasks, now)
    val active = records.current
    val done = records.history
    LazyColumn(Modifier.fillMaxSize().testTag("laundry-page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.LocalLaundryService, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column { Text("洗衣安排", style = MaterialTheme.typography.headlineSmall); Text("登记、等候与代取，都在这里", style = MaterialTheme.typography.bodyMedium) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LaundryStat("当前登记", if (tasksView) active.size else 0, Modifier.weight(1f))
                        LaundryStat("我的排队", state.queues.count { it.optBoolean("mine") }, Modifier.weight(1f))
                    }
                    if (tasksView) Button(onClick = { picker = true }, enabled = !busy && tasksOperate && state.machines.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("laundry-register")) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("登记一次洗衣")
                    }
                }
            }
        }
        item { FeatureError(session) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (socialView) LaundryShortcut("朋友与代取", "查看朋友请求，或请朋友帮忙", Icons.Outlined.PeopleOutline) { social = true }
                if (permissions?.optJSONObject("queue")?.optBoolean("view", true) != false) LaundryShortcut("预约提醒", "查看机器结束与排队通知", Icons.Outlined.NotificationsNone) { queueNotices = true }
            }
        }
        if (tasksView) {
            item { Text("当前洗衣", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp)) }
            if (active.isEmpty()) item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("今天还没有洗衣安排", style = MaterialTheme.typography.titleMedium)
                        Text("选一台空闲机器登记，结束前会按你的设置提醒。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(active, key = { it.id }) { task ->
                val raw = taskDetails[task.id]
                val finished = task.completionConfirmed || raw?.str("takeState") == "pending" || task.timingSource != "upstream" && task.endAt?.let { it <= now + task.clockOffsetMs } == true
                LaundryTaskCard(task, raw, now, finished, busy, tasksOperate, socialView,
                    take = { confirm = TaskConfirmation(task, false) }, pickup = { pickupTaskId = task.id },
                    delete = { confirm = TaskConfirmation(task, true) })
            }
        }
        if (state.queues.isNotEmpty()) item { Text("洗衣排队", style = MaterialTheme.typography.titleLarge) }
        items(state.queues, key = { "queue." + it.str("id", it.str("machineId")) }) { queue ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        if (inspectionView) item {
            Card(onClick = { inspections = true }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FactCheck, null)
                Column { Text("楼栋巡检", style = MaterialTheme.typography.titleMedium); Text("整轮检查，仅记录异常；全景照片选填", style = MaterialTheme.typography.bodySmall) }
            } }
        }
        if (tasksView) {
            item { Text("登记满 72 小时自动归档，满 336 小时自动清除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { LaundryShortcut("历史洗衣记录 · ${done.size}", if (history) "点击收起" else "查看已取衣和归档记录", Icons.Outlined.History) { history = !history } }
            if (history) {
                item { OutlinedTextField(historyQuery, { historyQuery = it.take(100) }, label = { Text("按机器名称或备注搜索") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                val filtered = done.filter { historyQuery.isBlank() || it.name.contains(historyQuery, true) || it.note.contains(historyQuery, true) }
                if (filtered.isEmpty()) item { Text(if (done.isEmpty()) "暂时没有归档的洗衣记录。" else "没有匹配的洗衣记录。") }
                items(filtered, key = { "done.${it.id}" }) { task -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (task.done) "已取衣 · 已归档" else "已归档 · 未确认取衣", color = MaterialTheme.colorScheme.primary)
                    task.startAt?.let { Text(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), style = MaterialTheme.typography.bodySmall) }
                    TaskDetailText(task, taskDetails[task.id])
                    TextButton(onClick = { confirm = TaskConfirmation(task, true) }, enabled = !busy && tasksOperate) { Text("删除记录", color = MaterialTheme.colorScheme.error) }
                } } }
            }
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
    if (inspections) InspectionScreen(state, vm) { inspections = false }
    if (queueNotices) QueueNoticeDialog(state, vm) { queueNotices = false }
}

@Composable private fun LaundryStat(label: String, count: Int, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = .6f), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable private fun LaundryShortcut(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Icon(icon, null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable internal fun LaundryTaskCard(task: LaundryTask, raw: JSONObject?, now: Long, finished: Boolean, busy: Boolean,
                                       tasksOperate: Boolean, socialView: Boolean, take: () -> Unit, pickup: () -> Unit, delete: () -> Unit) {
    var menu by remember(task.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().testTag("laundry-task:${task.id}"), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = if (finished) scheme.tertiaryContainer else scheme.secondaryContainer) {
                    Icon(if (task.kind == "dryer") Icons.Outlined.Air else Icons.Outlined.LocalLaundryService, null, Modifier.padding(11.dp).size(26.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (task.kind == "dryer") "烘干登记" else "洗衣登记", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("laundry-task-more:${task.id}")) { Icon(Icons.Outlined.MoreHoriz, "登记操作") }
                    DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("删除登记", color = scheme.error) }, onClick = { menu = false; delete() }, enabled = !busy && tasksOperate, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }) }
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = if (finished) scheme.tertiaryContainer else scheme.primaryContainer) {
                Text(laundryTimeLabel(task, now), Modifier.padding(16.dp).testTag("laundry-task-time:${task.id}"), style = MaterialTheme.typography.titleLarge,
                    color = if (finished) scheme.onTertiaryContainer else scheme.onPrimaryContainer)
            }
            TaskDetailText(task, raw)
            if (!task.done && (finished || socialView)) {
                HorizontalDivider(color = scheme.outlineVariant)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (finished) Button(onClick = take, enabled = !busy && tasksOperate, modifier = Modifier.heightIn(min = 48.dp).testTag("laundry-take:${task.id}")) { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("我已取衣") }
                    if (socialView) OutlinedButton(onClick = pickup, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("laundry-pickup:${task.id}")) { Text("请 TA 代取") }
                }
            }
        }
    }
}

@Composable private fun TaskDetailText(task: LaundryTask, raw: JSONObject?) {
    raw?.let {
        Text(when {
            task.timingSource == "upstream" -> "按最新平台时间同步" + if (task.timeOffsetMinutes != 0) " · 校准 ${if(task.timeOffsetMinutes > 0) "+" else ""}${task.timeOffsetMinutes} 分钟" else ""
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
    if (task.completionConfirmed) return "平台已确认完成 · 等待取衣"
    val end = task.endAt ?: return "结束时间待确认"
    val platformNow = now + task.clockOffsetMs
    if (end > platformNow) return "${if(task.timingSource == "upstream") "平台" else "预计"}剩余 ${((end - platformNow + 59_999) / 60_000).coerceAtLeast(1)} 分钟"
    if (task.timingSource == "upstream") return "即将完成"
    val overdue = ((platformNow - end) / 60_000).coerceAtLeast(0)
    return if (overdue == 0L) "登记计时已到 · 请现场确认" else "登记计时结束${when { overdue < 60 -> "$overdue 分钟"; overdue < 1440 -> "${overdue / 60} 小时"; else -> "${overdue / 1440} 天" }} · 请确认机器和衣物"
}
