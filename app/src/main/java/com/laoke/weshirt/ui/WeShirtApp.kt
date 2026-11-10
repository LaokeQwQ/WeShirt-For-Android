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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun WeShirtApp(vm: AppViewModel, onLogin: () -> Unit, onRequestNotifications: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    WeShirtTheme(state.appearance, state.palette) {
        val snackbar = remember { SnackbarHostState() }
        val lifecycle = LocalLifecycleOwner.current
        var configure by remember { mutableStateOf(false) }
        var buildingPicker by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<Machine?>(null) }
        LaunchedEffect(state.endpoint, state.profile?.id) { selected = null }
        LaunchedEffect(lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) { vm.refresh(); delay(45_000) } } }
        LaunchedEffect(state.error, state.info) { (state.error ?: state.info)?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.clearFeedback() } }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Column { Text(if(state.tab == 0) "WeShirt" else listOf("洗衣房", "我的洗衣", "频道", "我的")[state.tab], style = MaterialTheme.typography.titleLarge); if(state.tab == 0) Text("把等衣的时间，留给生活", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = {
                if(state.tab == 0) FilledTonalButton(onClick = { buildingPicker = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp)); Text(state.buildings.find { it.code == state.building }?.name ?: "选择楼栋") }
                IconButton(onClick = { vm.refresh(forceMetadata = true) }, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "刷新") }
            })
        }, bottomBar = {
            NavigationBar { listOf(Triple("洗衣房", Icons.Outlined.LocalLaundryService, 0), Triple("我的洗衣", Icons.Outlined.Schedule, 1), Triple("频道", Icons.Outlined.ChatBubbleOutline, 2), Triple("我的", Icons.Outlined.PersonOutline, 3)).forEach { (label, icon, index) ->
                NavigationBarItem(selected = state.tab == index, onClick = { vm.tab(index) }, icon = { BadgedBox(badge = { if(index == 2 && state.messages.any { it.mentioned }) Badge { Text("@") } }) { Icon(icon, label) } }, label = { Text(label) })
            } }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.endpoint.isBlank()) EmptyPanel("连接你的洗衣房", "先填写已部署的 WeShirt 服务地址。账号、楼栋和洗衣数据由该服务管理。", Icons.Outlined.CloudQueue, "设置服务地址") { configure = true }
                else if(state.building.isBlank() && state.tab == 0) EmptyPanel("住在哪一栋？", "选好后，下次打开直接进入。登录后会同步账号绑定的住宿楼。", Icons.Outlined.Apartment, "选择住宿楼") { buildingPicker = true }
                else AnimatedContent(targetState = state.tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "page") { tab ->
                    when(tab) {
                        0 -> RoomScreen(state, onMachine = { selected = it }, onQuality = vm::message)
                        1 -> LaundryScreen(state, vm, onLogin)
                        2 -> key(state.endpoint, state.profile?.id) { ChannelScreen(state, vm, onLogin) }
                        else -> SettingsScreen(state, vm, onLogin, onRequestNotifications, { configure = true }, { buildingPicker = true })
                    }
                }
            }
        }
        if(configure) EndpointDialog(state.endpoint, { configure = false }) { vm.configure(it); configure = false }
        if(buildingPicker) AlertDialog(onDismissRequest = { buildingPicker = false }, title = { Text("选择住宿楼") }, text = {
            Column { if(state.buildings.isEmpty()) Text("暂无可用楼栋，请先检查服务地址并刷新")
                state.buildings.forEach { b -> TextButton(onClick = { vm.selectBuilding(b.code); buildingPicker = false }, modifier = Modifier.fillMaxWidth()) { Text(b.name); if(b.code == state.building) Icon(Icons.Outlined.Check, null) } }
                if(state.profile != null) Text("每人仅可自行修改一次，之后请联系管理员。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { buildingPicker = false }) { Text("返回") } })
        selected?.let { machine -> MachineSheet(state.machines.find { it.id == machine.id } ?: machine, state, vm, { selected = null }, onLogin) }
        state.announcements.firstOrNull()?.let { announcement -> AlertDialog(onDismissRequest = { vm.dismissAnnouncement(false) }, title = { Text(announcement.str("title", "公告")) }, text = { LazyColumn { item { NativeMarkdown(announcement.str("markdown", announcement.str("content"))) } } }, dismissButton = { TextButton(onClick = { vm.dismissAnnouncement(false) }, enabled = !state.busy) { Text("退出") } }, confirmButton = { TextButton(onClick = { vm.dismissAnnouncement(true) }, enabled = !state.busy) { Text("不再提醒并退出") } }) }
    }
}
@Composable fun EmptyPanel(title: String, subtitle: String, icon: ImageVector, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(32.dp)); Icon(icon, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(action != null) Button(onClick = onAction) { Text(action) }
    }
}
@Composable private fun EndpointDialog(value: String, close: () -> Unit, save: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(onDismissRequest = close, title = { Text("连接服务") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("请填写你使用的 WeShirt 服务地址"); OutlinedTextField(text, { text = it }, label = { Text("https://example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Text("切换服务会退出当前账号。", style = MaterialTheme.typography.bodySmall) } }, dismissButton = { TextButton(onClick = close) { Text("取消") } }, confirmButton = { TextButton(onClick = { save(text) }) { Text("连接") } })
}
@Composable fun NativeMarkdown(text: String) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface
    val markwon = remember(context) { io.noties.markwon.Markwon.builder(context).usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context)).usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create()).usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) { builder.linkResolver { view, link -> if(Uri.parse(link).scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }
    }).build() }
    androidx.compose.ui.viewinterop.AndroidView(factory = { android.widget.TextView(it).apply { textSize = 16f; setLineSpacing(6f, 1.1f) } }, update = { view -> view.setTextColor(android.graphics.Color.argb(255, (color.red*255).toInt(), (color.green*255).toInt(), (color.blue*255).toInt())); markwon.setMarkdown(view, text) })
}
