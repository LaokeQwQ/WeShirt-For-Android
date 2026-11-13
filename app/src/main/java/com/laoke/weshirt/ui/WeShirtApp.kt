@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.laoke.weshirt.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.laoke.weshirt.ui.assistant.AssistantScreen
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun WeShirtApp(vm: AppViewModel, onLogin: () -> Unit, onRequestNotifications: () -> Unit, onRequestBackground: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(state)
    WeShirtTheme(state.appearance, state.palette) {
        val snackbar = remember { SnackbarHostState() }
        val lifecycle = LocalLifecycleOwner.current
        var buildingPicker by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<Machine?>(null) }
        var backgroundSettings by remember { mutableStateOf(false) }
        var display by remember(state.endpoint, state.profile?.id) { mutableStateOf(false) }
        var queueInbox by remember(state.endpoint, state.profile?.id) { mutableStateOf(false) }
        val seenQueueNotices = remember(state.endpoint, state.profile?.id) { mutableSetOf<String>() }
        LaunchedEffect(state.endpoint, state.profile?.id, state.building) { selected = null }
        LaunchedEffect(lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) { vm.refresh().join(); delay(30_000) } } }
        LaunchedEffect(state.error, state.info) { (state.error ?: state.info)?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.clearFeedback() } }
        LaunchedEffect(state.endpoint, state.profile?.id, state.queueNotices) {
            val fresh = state.queueNotices.filter { it.optBoolean("active") && it.str("readAt").isBlank() && it.str("id") !in seenQueueNotices }
            fresh.forEach { seenQueueNotices.add(it.str("id")) }
            if (state.authReady && state.profile?.notifyOn != false && fresh.isNotEmpty() && !display) {
                val newest = fresh.first()
                if (snackbar.showSnackbar(newest.str("title", "预约提醒") + " · " + newest.str("machineName"), "查看", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) queueInbox = true
            }
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Column { Text(if(state.tab == 0) "WeShirt" else when (state.tab) { 1 -> "我的洗衣"; 2 -> "频道"; 4 -> "AI 助手"; else -> "我的" }, style = MaterialTheme.typography.titleLarge); if(state.tab == 0) Text("把等衣的时间，留给生活", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = {
                if(state.tab == 0) FilledTonalButton(onClick = { buildingPicker = true }, enabled = state.authReady, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp)); Text(state.buildings.find { it.code == state.building }?.name ?: "选择楼栋") }
                if (state.tab == 0) IconButton(onClick = { display = true }, enabled = state.building.isNotBlank()) { Icon(Icons.Outlined.Fullscreen, "大屏看板") }
                IconButton(onClick = { vm.refresh(forceMetadata = true) }, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "刷新") }
            })
        }, bottomBar = {
            WeShirtNavigation(state, vm::tab)

        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!state.authReady && (state.tab != 0 || state.building.isBlank())) SessionRestoringPanel(state, { vm.refresh() })
                else if(state.building.isBlank() && state.tab == 0) EmptyPanel("住在哪一栋？", "选好后，下次打开直接进入。登录后会同步账号绑定的住宿楼。", Icons.Outlined.Apartment, "选择住宿楼") { buildingPicker = true }
                else AnimatedContent(targetState = state.tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "page") { tab ->
                    when(tab) {
                        0 -> RoomScreen(state, onMachine = { selected = it }, onQuality = vm::message, allow3D = state.building.equals("guiyuan", ignoreCase = true))
                        1 -> LaundryScreen(state, vm, onLogin)
                        2 -> key(state.endpoint, state.profile?.id) { ChannelScreen(state, vm, onLogin) }
                        4 -> AssistantScreen(state, vm, onLogin)
                        else -> SettingsScreen(state, vm, onLogin, onRequestNotifications, { buildingPicker = true }, { backgroundSettings = true })
                    }
                }
            }
        }
        // Machine data is public. Account restoration must not hold the room or
        // its read-only details behind the private-feature gate.
        selected?.let { machine -> key(state.endpoint, state.profile?.id, state.building, machine.id) { MachineSheet(state.machines.find { it.id == machine.id } ?: machine, state, vm, { selected = null }, onLogin) } }
        if (display) DisplayScreen(state) { display = false }
        if (!state.authReady) return@WeShirtTheme
        if(buildingPicker) AlertDialog(onDismissRequest = { buildingPicker = false }, title = { Text("选择住宿楼") }, text = {
            Column { if(state.buildings.isEmpty()) Text("暂无可用楼栋，请稍后刷新")
                state.buildings.forEach { b -> TextButton(onClick = { vm.selectBuilding(b.code); buildingPicker = false }, modifier = Modifier.fillMaxWidth()) { Text(b.name); if(b.code == state.building) Icon(Icons.Outlined.Check, null) } }
                if(state.profile != null) Text("每人仅可自行修改一次，之后请联系管理员。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { buildingPicker = false }) { Text("返回") } })
        if (state.announcements.isEmpty()) ReminderPermissionGuidance(state, backgroundSettings, { backgroundSettings = false }, onRequestNotifications, onRequestBackground)
        OverdueCollectionPrompt(state, vm, enabled = !display && selected == null && state.pendingShare == null && !buildingPicker && !backgroundSettings && !queueInbox)
        if (queueInbox && state.profile != null) com.laoke.weshirt.ui.social.QueueNoticeDialog(state, vm) { queueInbox = false }
        state.pendingShare?.takeIf { it.origin == state.endpoint }?.let { share ->
            if (state.profile == null) AlertDialog(onDismissRequest = vm::dismissShare, title = { Text(if (share.kind == "skill") "导入洗衣助手 Skill" else "朋友邀请") }, text = { Text("登录当前服务后可以查看邀请；确认后才会添加。") }, confirmButton = { TextButton(onClick = onLogin, enabled = !state.busy) { Text("钉钉登录") } }, dismissButton = { TextButton(onClick = vm::dismissShare) { Text("取消") } })
            else if (share.kind == "skill") com.laoke.weshirt.ui.assistant.AssistantDialog(state, vm, initialSkill = share.ticket, close = vm::dismissShare)
            else com.laoke.weshirt.ui.social.SocialDialog(state, vm, close = vm::dismissShare, initialInvite = share.ticket)
        }
        state.announcements.firstOrNull()?.let { announcement -> AlertDialog(onDismissRequest = { vm.dismissAnnouncement(false) }, title = { Text(announcement.str("title", "公告")) }, text = { LazyColumn { item { NativeMarkdown(announcement.str("markdown", announcement.str("content"))) } } }, dismissButton = { TextButton(onClick = { vm.dismissAnnouncement(false) }, enabled = !state.busy) { Text("退出") } }, confirmButton = { TextButton(onClick = { vm.dismissAnnouncement(true) }, enabled = !state.busy) { Text("不再提醒并退出") } }) }
    }
}
@Composable internal fun WeShirtNavigation(state: AppState, select: (Int) -> Unit) {
    NavigationBar {
        listOf(Triple("洗衣房", Icons.Outlined.LocalLaundryService, 0), Triple("我的洗衣", Icons.Outlined.Schedule, 1),
            Triple("AI 助手", Icons.Outlined.AutoAwesome, 4), Triple("频道", Icons.Outlined.ChatBubbleOutline, 2), Triple("我的", Icons.Outlined.PersonOutline, 3)).forEach { (label, icon, index) ->
            NavigationBarItem(selected = state.tab == index, onClick = { select(index) }, modifier = Modifier.testTag("navigation:$index"),
                icon = { BadgedBox(badge = {
                    if (index == 2 && state.messageNotices.any { it.str("readAt").isBlank() }) Badge(containerColor = androidx.compose.ui.graphics.Color(0xFFD87717)) { Text("@") }
                    if (index == 1 && state.queueNotices.any { it.optBoolean("active") && it.str("readAt").isBlank() }) Badge { Text("!") }
                }) { Icon(icon, label) } }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@Composable internal fun SessionRestoringPanel(state: AppState, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp).testTag("session-restoring"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.AccountCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(if (state.sessionRestoring) "正在恢复账号" else "暂时未能连接", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(state.authError ?: "正在同步资料，请稍候。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.authError != null) TextButton(onClick = retry, enabled = !state.sessionRestoring) { Text("重试恢复") }
    }
}

@Composable fun EmptyPanel(title: String, subtitle: String, icon: ImageVector, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(32.dp)); Icon(icon, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(action != null) Button(onClick = onAction) { Text(action) }
    }
}
@Composable fun NativeMarkdown(text: String) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface
    val markwon = remember(context) { io.noties.markwon.Markwon.builder(context).usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context)).usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create()).usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) { builder.linkResolver { _, link -> if(Uri.parse(link).scheme == "https") runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }; Unit } }
    }).build() }
    val parsed = remember(text, markwon) { markwon.parse(text) }
    val images = remember(parsed) {
        val found = mutableListOf<String>()
        val nodes = java.util.ArrayDeque<org.commonmark.node.Node>().apply { add(parsed) }
        while (nodes.isNotEmpty() && found.size < 12) {
            val node = nodes.removeFirst()
            if (node is org.commonmark.node.Image) {
                val uri = runCatching { java.net.URI(node.destination) }.getOrNull()
                if (uri?.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrBlank() && node.destination.length <= 2000) found.add(node.destination)
            }
            var child = node.firstChild
            while (child != null) { nodes.addLast(child); child = child.next }
        }
        found.distinct()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { android.widget.TextView(it).apply { textSize = 16f; setLineSpacing(6f, 1.1f) } }, update = { view -> view.setTextColor(android.graphics.Color.argb(255, (color.red*255).toInt(), (color.green*255).toInt(), (color.blue*255).toInt())); markwon.setParsedMarkdown(view, markwon.render(parsed)) })
        images.forEach { address -> coil.compose.AsyncImage(coil.request.ImageRequest.Builder(context).data(address).size(1200).build(), "Markdown 图片", Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
    }
}
