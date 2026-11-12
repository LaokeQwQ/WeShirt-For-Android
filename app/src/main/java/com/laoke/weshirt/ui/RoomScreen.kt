@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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

@Composable fun RoomScreen(state: AppState, onMachine: (Machine) -> Unit, onQuality: (String) -> Unit, allow3D: Boolean = true) {
    var threeD by rememberSaveable(state.endpoint, state.building, allow3D) { mutableStateOf(allow3D) }
    var rendererUnavailable by rememberSaveable(state.endpoint, state.building) { mutableStateOf(false) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<LaundryRoomView?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var facility by remember { mutableStateOf<RoomFacility?>(null) }
    var selectedMachineId by rememberSaveable(state.endpoint, state.building) { mutableStateOf<String?>(null) }
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
    val projection = remember(state.machines, state.queues, now / 30_000) {
        val counts = state.queues.groupingBy { it.str("machineId") }.eachCount()
        projectWebRoom(state.machines, now).let { room -> room.copy(machines = room.machines.map { it.copy(queueCount = counts[it.id] ?: 0) }) }
    }
    val showing3D = allow3D && threeD && !rendererUnavailable && projection.hasBoundMachines
    DisposableEffect(owner, renderer) {
        val room = renderer
        val observer = LifecycleEventObserver { _, event -> when(event) { Lifecycle.Event.ON_RESUME -> room?.lifecycleResume(); Lifecycle.Event.ON_PAUSE -> room?.lifecyclePause(); Lifecycle.Event.ON_DESTROY -> room?.lifecyclePause(); else -> Unit } }
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) room?.lifecycleResume() else room?.lifecyclePause()
        onDispose { owner.lifecycle.removeObserver(observer); room?.lifecyclePause() }
    }
    LaunchedEffect(notice) { if(notice != null) { delay(3000); notice = null } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val roomHeight = maxHeight
    val compact = roomHeight < 480.dp
    val compactScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().let { if (showing3D && compact) it.verticalScroll(compactScroll) else it }, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        state.boardWarning?.let { warning ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("board-freshness-warning"), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(warning, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if(allow3D) RoomViewModeSwitch(showing3D, projection.hasBoundMachines,
            showRoom = { rendererUnavailable = false; threeD = true }, showList = { threeD = false })
        if(showing3D) {
            Box(Modifier.fillMaxWidth().let { if (compact) it.height((roomHeight - 172.dp).coerceAtLeast(180.dp)) else it.weight(1f) }.padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp)).testTag("native-room-scene")) {
                AndroidView(factory = { context -> LaundryRoomView(context).also { view ->
                    renderer = view
                    view.onMachineSelected = { id -> currentMachines.find { it.id == id }?.let { selectedMachineId = id; currentOnMachine(it) } ?: currentOnQuality("该机位尚未绑定设备，请在机器列表选择") }
                    view.onFacilitySelected = { facility = it }
                    view.onQualityChanged = { quality -> if(quality.showDowngradeNotice) notice = "为保持流畅，已自动调整画质 · ${quality.targetFps} 帧" }
                    view.onRendererError = { _ ->
                        // Scene setup is optional. The list remains the source of truth
                        // when a device/driver cannot create the native room.
                        rendererUnavailable = true
                        threeD = false
                    }
                } }, modifier = Modifier.fillMaxSize(), onRelease = { released ->
                    released.dispose()
                    if(renderer === released) renderer = null
                }, update = { it.setMachines(projection.machines); it.setPerformanceMode(runCatching { PerformanceMode.valueOf(state.quality) }.getOrDefault(PerformanceMode.AUTO)) })
                FilledTonalIconButton(onClick = { renderer?.resetCamera() }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Icon(Icons.Outlined.RestartAlt, "返回房间全景")
                }
                Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("空闲" to Color(0xFF16803D), "使用中" to Color(0xFFB46909), "故障" to Color(0xFFDC2626)).forEach { (label, color) ->
                            Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if(machines.isEmpty()) Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                    Text(if(onlyFavorites) "还没有收藏机器" else "当前楼栋暂无机器数据", Modifier.padding(20.dp))
                }
                if(notice != null) Surface(Modifier.align(Alignment.TopCenter).padding(16.dp), color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(16.dp)) { Text(notice!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall) }
            }
            RoomMachineShortcuts(
                machines = if (onlyFavorites) projection.machines.filter { it.id in state.favorites } else projection.machines,
                selectedId = selectedMachineId,
                onlyFavorites = onlyFavorites,
                onFavorites = { onlyFavorites = !onlyFavorites },
                onSelect = { target -> currentMachines.find { it.id == target.id }?.let { machine ->
                    selectedMachineId = machine.id
                    renderer?.focusMachine(machine.id)
                    onMachine(machine)
                } },
                onFacilities = { facility = RoomFacility.BOILER },
                compact = compact
            )
            if (!compact) Text("拖动环视 · 双指缩放 · 双击全景", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact && onlyFavorites && machines.isEmpty()) Text("在机器详情中收藏后，可在这里快速选择。", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
        }
        if(!showing3D) MachineBoard(
            machines = state.machines,
            favorites = state.favorites,
            now = now,
            updatedAt = state.updatedAt,
            onMachine = onMachine,
            modifier = Modifier.weight(1f),
            header = if(rendererUnavailable) ({
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("已切换机器列表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { rendererUnavailable = false; threeD = true }) { Text("重试 3D") }
                }
            }) else null,
        ) else if (!compact) FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val idle = machines.count { it.state == "idle" && !it.suspectedUncollected }
            Text("${machines.size} 台机器", style = MaterialTheme.typography.labelLarge)
            Text("$idle 台空闲", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("${machines.count { it.state == "occupied" }} 台使用中", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(2.dp))
    }
    }
    facility?.let { selected ->
        val rules = when (selected) {
            RoomFacility.BOILER -> listOf("刷卡后接水，接满即关", "暖瓶放进接水区，不要手持对嘴", "开水很烫，接水时站在侧面", "接完把接水盘的水沥干")
            RoomFacility.WASHER_NOTICE -> listOf("先在这台机器上登记，再按启动", "洗完及时取衣，疑似未取状态需要现场确认", "故障先看现场，再在机器上留言", "地面有水先拖干，再开始下一筒")
            RoomFacility.DRYER_NOTICE -> listOf("衣物先脱水，再放进烘干机", "到时及时取出，避免回潮", "滤网有毛絮就清掉", "烘干中途开门，先等滚筒停稳")
        }
        AlertDialog(onDismissRequest = { facility = null }, title = { Text(when(selected) { RoomFacility.BOILER -> "开水机使用须知"; RoomFacility.WASHER_NOTICE -> "洗衣机使用须知"; RoomFacility.DRYER_NOTICE -> "烘干机使用须知" }) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoomFacility.entries.forEach { option -> FilterChip(selected == option, { facility = option }, label = { Text(when(option) { RoomFacility.BOILER -> "开水机"; RoomFacility.WASHER_NOTICE -> "洗衣机"; RoomFacility.DRYER_NOTICE -> "烘干机" }, maxLines = 1) }) }
                }
                rules.forEachIndexed { index, line -> Text("${index + 1}. $line", style = MaterialTheme.typography.bodyMedium) }
            }
        }, confirmButton = { TextButton(onClick = { facility = null }) { Text("知道了") } })
    }
}

/** The mode controls keep the full row so text and touch targets never compete with a trailing chip. */
@Composable
internal fun RoomViewModeSwitch(showing3D: Boolean, roomAvailable: Boolean, showRoom: () -> Unit, showList: () -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("room-view-mode")) {
        SegmentedButton(showing3D, showRoom, SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = 48.dp).testTag("room-mode-3d"), enabled = roomAvailable, icon = {}) {
            Text("3D 洗衣房", maxLines = 2)
        }
        SegmentedButton(!showing3D, showList, SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = 48.dp).testTag("room-mode-list"), icon = {}) {
            Text("机器列表", maxLines = 2)
        }
    }
}

/** Mirrors the Web room's fixed physical slot order, rather than sorting buttons by changing availability. */
@Composable
internal fun RoomMachineShortcuts(machines: List<RoomMachine>, selectedId: String?, onlyFavorites: Boolean, onFavorites: () -> Unit, onSelect: (RoomMachine) -> Unit, onFacilities: () -> Unit, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("机位快捷选择", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            FilterChip(onlyFavorites, onFavorites, label = { Text("常用") }, modifier = Modifier.heightIn(min = 48.dp).testTag("room-favorites"), leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) })
            IconButton(onClick = onFacilities, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Info, "设施须知") }
        }
        LazyRow(Modifier.fillMaxWidth().testTag("room-machine-shortcuts"), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(machines, key = { it.id }) { machine ->
                val bound = !machine.id.startsWith("unbound:")
                val status = when (machine.state) {
                    RoomMachineState.IDLE -> "空闲"
                    RoomMachineState.RUNNING, RoomMachineState.FINISHING -> "使用中"
                    RoomMachineState.AWAITING_PICKUP -> "疑似未取"
                    RoomMachineState.REVIEW -> "待复核"
                    RoomMachineState.FAULT -> "暂不可用"
                    RoomMachineState.UNKNOWN -> if (bound) "状态待更新" else "未绑定"
                }
                val scheme = MaterialTheme.colorScheme
                val colors = when (machine.state) {
                    RoomMachineState.IDLE -> scheme.primaryContainer to scheme.onPrimaryContainer
                    RoomMachineState.RUNNING, RoomMachineState.FINISHING -> scheme.secondaryContainer to scheme.onSecondaryContainer
                    RoomMachineState.AWAITING_PICKUP, RoomMachineState.REVIEW -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                    RoomMachineState.FAULT -> scheme.errorContainer to scheme.onErrorContainer
                    RoomMachineState.UNKNOWN -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
                }
                val selected = selectedId == machine.id
                OutlinedButton(
                    onClick = { onSelect(machine) }, enabled = bound,
                    modifier = Modifier.widthIn(min = 60.dp).heightIn(min = 52.dp).testTag("room-slot:${machine.slot}").semantics {
                        contentDescription = "${machine.slot}号${if (machine.kind == "dryer") "烘干机" else "洗衣机"} · ${machine.label}"
                        stateDescription = status + if (selected) " · 已选择" else ""
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) scheme.primary else colors.first, contentColor = if (selected) scheme.onPrimary else colors.second),
                    border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) { Text(machine.slot, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
            }
            if (compact) {
                item("favorites") { FilterChip(onlyFavorites, onFavorites, label = { Text("常用") }, modifier = Modifier.heightIn(min = 52.dp).testTag("room-favorites"), leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) }) }
                item("facilities") { IconButton(onClick = onFacilities, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.Info, "设施须知") } }
            }
        }
    }
}

@Composable fun MachineSheet(machine: Machine, state: AppState, vm: AppViewModel, close: () -> Unit, login: () -> Unit) {
    val session = rememberMachineActionSession(state, vm)
    val frozen = MachineActionSnapshot(state.endpoint, state.profile?.id, state.building, machine)
    var registering by remember { mutableStateOf(false) }
    var commentsVisible by remember { mutableStateOf(false) }
    var contact by remember { mutableStateOf(false) }
    var favoriteConfirmation by remember { mutableStateOf<MachineActionSnapshot?>(null) }
    var registrationError by remember { mutableStateOf<String?>(null) }
    var moreActions by remember(machine.id) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = System.currentTimeMillis(); delay(30_000) }
        }
    }
    val currentClose by rememberUpdatedState(close)
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 560.dp) {
        MachineSheetFrame(machine, machine.id in state.favorites, !state.busy && !session.busy, close,
            favoriteClick = { if(state.profile == null) login() else favoriteConfirmation = frozen },
            register = { if(state.profile == null) login() else { registrationError = null; registering = true } }) {
            session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            session.feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.boardWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            machineBoardStatus(machine, now).detail?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text("设备状态以现场为准；预计时间归零后仍需确认是否结束。", style = MaterialTheme.typography.bodySmall)
            if(machine.note.isNotBlank()) Text("公开备注：${machine.note}")
            if(machine.disinfectant || machine.selfCleaned) Text(listOfNotNull("已使用消毒液".takeIf { machine.disinfectant }, "已做自清洁".takeIf { machine.selfCleaned }).joinToString(" · "))
            if(machine.health.isNotBlank()) Text(machine.health, color = MaterialTheme.colorScheme.error)
            if(machine.suspectedUncollected) Text("疑似未取衣 · 请先确认现场情况", color = MaterialTheme.colorScheme.tertiary)
            OutlinedButton(onClick = { if(state.profile == null) login() else commentsVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("查看这台机器的留言") }
            if(machine.occupantId.isNotBlank() && machine.occupantId != state.profile?.id) {
                OutlinedButton(onClick = { if(state.profile == null) login() else contact = true }, enabled = !state.busy && !session.busy, modifier = Modifier.fillMaxWidth()) { Text("联系使用者") }
            }
            MachineFeedback(machine, state, vm, session, login)
            OutlinedButton(onClick = { moreActions = !moreActions }, modifier = Modifier.fillMaxWidth().testTag("machine-more-actions")) { Text(if (moreActions) "收起排队与现场反馈" else "排队预约与现场反馈") }
            if (moreActions) MachineActions(machine, state, vm, session, login)
        }
    }
    if(registering) RegisterDialog(machine, state.busy || session.busy, registrationError, { registering = false }) { minutes, note, disinfectant, cleaned, useMachine, selfClean ->
        registrationError = null
        try {
            session.validate(frozen, "tasks")
            vm.register(machine, minutes, note, disinfectant, cleaned, useMachine, selfClean,
                onSuccess = { if (active) { registering = false; currentClose() } }, onFailure = { if (active) registrationError = it })
        } catch (problem: Exception) { registrationError = clientFailure(problem, "机器已变化，请重新核对") }
    }
    favoriteConfirmation?.let { target -> AlertDialog(onDismissRequest = { favoriteConfirmation = null }, title = { Text(if (machine.id in state.favorites) "取消收藏机器？" else "收藏这台机器？") }, text = { Text(target.machine.name) }, confirmButton = { TextButton(enabled = !state.busy && !session.busy, onClick = {
        favoriteConfirmation = null
        session.run(target, "") { request(target, "/api/user/favorites/toggle", org.json.JSONObject().put("machineId", target.machine.id), resource = ""); feedback = "常用机器已更新" }
    }) { Text("确认") } }, dismissButton = { TextButton(onClick = { favoriteConfirmation = null }) { Text("取消") } }) }
    if (contact && machine.occupantId.isNotBlank() && machine.occupantId != state.profile?.id) key(machine.occupantId) { com.laoke.weshirt.ui.social.ContactUserDialog(machine.occupantId, machine.id, state, vm) { contact = false } }
    if (commentsVisible) MachineCommentsDialog(machine, state, vm) { commentsVisible = false }
}

/** Header and primary action stay reachable while the details scroll, including short landscape windows. */
@Composable
internal fun MachineSheetFrame(machine: Machine, favorite: Boolean, enabled: Boolean, close: () -> Unit, favoriteClick: () -> Unit = {}, register: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().testTag("machine-sheet")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(machine.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (machine.originalName.isNotBlank() && machine.originalName != machine.name) Text("原名：${machine.originalName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(machine.stateLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = favoriteClick, enabled = enabled) { Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (favorite) "取消收藏机器" else "收藏机器") }
            IconButton(onClick = close, modifier = Modifier.testTag("machine-sheet-close")) { Icon(Icons.Outlined.Close, "关闭机器面板") }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("machine-sheet-details").padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        HorizontalDivider()
        Button(onClick = register, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp).testTag("machine-sheet-register"), enabled = enabled && machine.state != "faulty") { Text("登记我的${if (machine.kind == "dryer") "烘干" else "洗衣"}") }
    }
}
