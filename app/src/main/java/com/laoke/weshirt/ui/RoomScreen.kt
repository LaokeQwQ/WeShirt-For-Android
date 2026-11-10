@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import com.laoke.weshirt.room.*
import kotlinx.coroutines.delay

@Composable fun RoomScreen(state: AppState, onMachine: (Machine) -> Unit, onQuality: (String) -> Unit) {
    var threeD by rememberSaveable { mutableStateOf(true) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<LaundryRoomView?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    val currentMachines by rememberUpdatedState(state.machines)
    val currentOnMachine by rememberUpdatedState(onMachine)
    val currentOnQuality by rememberUpdatedState(onQuality)
    val now by produceState(System.currentTimeMillis(), owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = System.currentTimeMillis(); delay(30_000) }
        }
    }
    val machines = remember(state.machines, onlyFavorites, state.favorites, now) {
        state.machines.filter { !onlyFavorites || it.id in state.favorites }
            .sortedWith(compareBy<Machine> { it.availability(now) }.thenBy { it.slot.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.slot }.thenBy { it.id })
    }
    val roomMachines = remember(machines, now / 30_000) {
        machines.map { m -> RoomMachine(m.id, m.slot, m.name, when(m.state) {
            "idle" -> if(m.suspectedUncollected) RoomMachineState.AWAITING_PICKUP else RoomMachineState.IDLE
            // An estimate reaching zero cannot confirm a physical machine has stopped.
            "occupied" -> if (m.finishAt?.let { it - now in 1..180_000L } == true) RoomMachineState.FINISHING else RoomMachineState.RUNNING
            "pending_pickup", "awaiting_pickup" -> RoomMachineState.AWAITING_PICKUP
            "faulty" -> RoomMachineState.FAULT
            else -> RoomMachineState.UNKNOWN
        }, m.finishAt, m.kind) }
    }
    DisposableEffect(owner, renderer) {
        val room = renderer
        val observer = LifecycleEventObserver { _, event -> when(event) { Lifecycle.Event.ON_RESUME -> room?.lifecycleResume(); Lifecycle.Event.ON_PAUSE -> room?.lifecyclePause(); Lifecycle.Event.ON_DESTROY -> room?.lifecyclePause(); else -> Unit } }
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) room?.lifecycleResume() else room?.lifecyclePause()
        onDispose { owner.lifecycle.removeObserver(observer); room?.lifecyclePause() }
    }
    LaunchedEffect(notice) { if(notice != null) { delay(3000); notice = null } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SingleChoiceSegmentedButtonRow { SegmentedButton(threeD, { threeD = true }, SegmentedButtonDefaults.itemShape(0,2)) { Text("3D 洗衣房") }; SegmentedButton(!threeD, { threeD = false }, SegmentedButtonDefaults.itemShape(1,2)) { Text("机器列表") } }
            FilterChip(onlyFavorites, { onlyFavorites = !onlyFavorites }, label = { Text("常用") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) })
        }
        if(threeD) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp))) {
                AndroidView(factory = { context -> LaundryRoomView(context).also { view ->
                    renderer = view
                    view.onMachineSelected = { id -> currentMachines.find { it.id == id }?.let(currentOnMachine) }
                    view.onQualityChanged = { quality -> if(quality.showDowngradeNotice) notice = "为保持流畅，已自动调整画质 · ${quality.targetFps} 帧" }
                    view.onRendererError = { event ->
                        threeD = false
                        currentOnQuality("三维场景暂不可用，已切换列表 · $event")
                    }
                } }, modifier = Modifier.fillMaxSize(), onRelease = { released ->
                    released.dispose()
                    if(renderer === released) renderer = null
                }, update = { it.setMachines(roomMachines); it.setPerformanceMode(runCatching { PerformanceMode.valueOf(state.quality) }.getOrDefault(PerformanceMode.AUTO)) })
                if(machines.isEmpty()) Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                    Text(if(onlyFavorites) "还没有收藏机器" else "当前楼栋暂无机器数据", Modifier.padding(20.dp))
                }
                if(notice != null) Surface(Modifier.align(Alignment.TopCenter).padding(16.dp), color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(16.dp)) { Text(notice!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall) }
            }
            Text("拖动环视 · 双指缩放 · 轻点机器查看", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(machines, key = { it.id }) { machine ->
                    AssistChip(onClick = { onMachine(machine) }, label = { Text("${machine.name} · ${machine.stateLabel}") }, leadingIcon = {
                        Icon(if(machine.kind == "dryer") Icons.Outlined.Air else Icons.Outlined.LocalLaundryService, null, Modifier.size(18.dp))
                    })
                }
            }
        }
        if(!threeD) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("最快可用", style = MaterialTheme.typography.titleMedium) }
            if(machines.isEmpty()) item { Text(if(onlyFavorites) "还没有收藏机器" else "这个楼栋暂时没有可用的机器数据") }
            items(machines, key = { it.id }) { machine -> MachineCard(machine, machine.id in state.favorites) { onMachine(machine) } }
        } else Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val idle = machines.count { it.state == "idle" && !it.suspectedUncollected }
            Text("${machines.size} 台机器", style = MaterialTheme.typography.labelLarge)
            Text("$idle 台空闲", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("${machines.count { it.state == "occupied" }} 台使用中", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(2.dp))
    }
}
@Composable private fun MachineCard(machine: Machine, favorite: Boolean, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if(machine.kind == "dryer") Icons.Outlined.Air else Icons.Outlined.LocalLaundryService, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(machine.name, style = MaterialTheme.typography.titleMedium); Text(machine.stateLabel + (machine.minutes()?.let { " · 约 $it 分钟" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); if(machine.note.isNotBlank()) Text(machine.note, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
            if(favorite) Icon(Icons.Outlined.Star, "已收藏", tint = MaterialTheme.colorScheme.primary)
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}
@Composable fun MachineSheet(machine: Machine, state: AppState, vm: AppViewModel, close: () -> Unit, login: () -> Unit) {
    var registering by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { Text(machine.name, style = MaterialTheme.typography.headlineSmall); Text(machine.stateLabel, color = MaterialTheme.colorScheme.primary) }; IconButton(onClick = { if(state.profile == null) login() else vm.favorite(machine) }, enabled = !state.busy) { Icon(if(machine.id in state.favorites) Icons.Outlined.Star else Icons.Outlined.StarOutline, "收藏机器") } }
            machine.minutes()?.let { Text("预计剩余 $it 分钟", style = MaterialTheme.typography.titleLarge) }
            Text("设备状态以现场为准；预计时间归零后仍需确认是否结束。", style = MaterialTheme.typography.bodySmall)
            if(machine.note.isNotBlank()) Text("公开备注：${machine.note}")
            if(machine.disinfectant || machine.selfCleaned) Text(listOfNotNull("已使用消毒液".takeIf { machine.disinfectant }, "已做自清洁".takeIf { machine.selfCleaned }).joinToString(" · "))
            if(machine.health.isNotBlank()) Text(machine.health, color = MaterialTheme.colorScheme.error)
            if(machine.suspectedUncollected) Text("疑似未取衣 · 请先确认现场情况", color = MaterialTheme.colorScheme.tertiary)
            if(machine.occupantId.isNotBlank() && machine.occupantId != state.profile?.id) {
                var contact by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { if(state.profile == null) login() else contact = true }, modifier = Modifier.fillMaxWidth()) { Text("联系使用者") }
                if(contact) AlertDialog(onDismissRequest = { contact = false }, title = { Text("发送一条衣物提醒") }, text = { Column {
                    Text("对方确认前仅允许发送一条固定模板，确认后才能完整聊天。", style = MaterialTheme.typography.bodySmall)
                    listOf("forgot" to "衣服可能忘拿", "misplaced" to "衣服可能放错", "taken_wrong" to "衣服可能错拿", "left_behind" to "衣物可能漏拿").forEach { (code,label) -> TextButton(onClick = { vm.mutate("联系请求已发送") { vm.api.call("/api/social/contact", "POST", org.json.JSONObject().put("toUserId", machine.occupantId).put("machineId", machine.id).put("template", code)) }; contact = false }, enabled = !state.busy) { Text(label) } }
                } }, confirmButton = { TextButton(onClick = { contact = false }) { Text("取消") } })
            }
            Button(onClick = { if(state.profile == null) login() else registering = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && machine.state != "faulty") { Text("登记我的${if(machine.kind == "dryer") "烘干" else "洗衣"}") }
            OutlinedButton(onClick = { if(state.profile == null) login() else vm.queue(machine) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && machine.state != "faulty") { Text("等这台 · 加入排队") }
            Text("排队按当前剩余时间估算，结束前约 3 分钟和确认结束时提醒。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if(registering) RegisterDialog(machine, state.busy, { registering = false }) { minutes, note, disinfectant, cleaned, useMachine, selfClean -> vm.register(machine, minutes, note, disinfectant, cleaned, useMachine, selfClean); registering = false; close() }
}
@Composable private fun RegisterDialog(machine: Machine, busy: Boolean, close: () -> Unit, submit: (Int, String, Boolean, Boolean, Boolean, Boolean) -> Unit) {
    var minutes by remember { mutableStateOf(if(machine.kind == "dryer") "40" else "30") }
    var note by remember { mutableStateOf("") }; var disinfectant by remember { mutableStateOf(false) }; var cleaned by remember { mutableStateOf(false) }
    var useMachine by remember { mutableStateOf(machine.finishAt?.let { it > System.currentTimeMillis() } == true) }; var selfClean by remember { mutableStateOf(false) }; var confirm by remember { mutableStateOf(false) }
    val valid = minutes.toIntOrNull()?.let { it in (if(machine.kind == "dryer") 10 else 1)..180 } == true
    AlertDialog(onDismissRequest = close, title = { Text(if(confirm) "确认登记" else "登记 · ${machine.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(confirm) { Text("${machine.name}\n${if(selfClean) "桶自洁 · 6 分钟" else if(useMachine) "跟随机器剩余时间" else "${minutes} 分钟"}"); Text("备注、消毒液和自清洁信息将公开显示。"); if(note.isNotBlank()) Text(note) }
            else {
                if(machine.finishAt != null) Row { Checkbox(useMachine, { useMachine = it; if(it) selfClean = false }); Text("跟随设备剩余时间", Modifier.padding(top = 12.dp)) }
                OutlinedTextField(minutes, { if(it.length <= 3) minutes = it.filter(Char::isDigit) }, label = { Text("时长（分钟）") }, enabled = !useMachine && !selfClean, singleLine = true, modifier = Modifier.fillMaxWidth())
                if(machine.kind != "dryer") Row { Checkbox(selfClean, { selfClean = it; if(it) { minutes = "6"; useMachine = false } }); Text("桶自洁 · 6 分钟", Modifier.padding(top = 12.dp)) }
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("公开备注（选填）") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                Row { Checkbox(disinfectant, { disinfectant = it }); Text("使用消毒液", Modifier.padding(top = 12.dp)) }
                Row { Checkbox(cleaned, { cleaned = it }); Text("已完成自清洁", Modifier.padding(top = 12.dp)) }
            }
        }
    }, dismissButton = { TextButton(onClick = { if(confirm) confirm = false else close() }) { Text(if(confirm) "返回修改" else "取消") } }, confirmButton = { TextButton(enabled = !busy && (valid || useMachine), onClick = { if(confirm) submit(minutes.toIntOrNull() ?: 30, note, disinfectant, cleaned, useMachine, selfClean) else confirm = true }) { Text(if(confirm) "确认登记" else "下一步") } })
}
