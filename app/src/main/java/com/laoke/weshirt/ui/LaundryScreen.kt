package com.laoke.weshirt.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable fun LaundryScreen(state: AppState, vm: AppViewModel, login: () -> Unit) {
    val account = state.endpoint to state.profile?.id
    val screen = remember(account) { Any() }
    val currentScreen by rememberUpdatedState(screen)
    var invite by remember(account) { mutableStateOf<String?>(null) }
    var assistant by remember(account) { mutableStateOf(false) }
    var pickupFor by remember(account) { mutableStateOf<LaundryTask?>(null) }
    var thread by remember(account) { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while(true) { value = System.currentTimeMillis(); delay(30_000) } } }
    var scannerAccount by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val target = scannerAccount
        scannerAccount = null
        result.contents?.let {
            if(target == account && target?.second != null && target == (vm.state.value.endpoint to vm.state.value.profile?.id)) {
                val ticket = runCatching { android.net.Uri.parse(it).getQueryParameter("ticket") }.getOrNull() ?: it
                vm.acceptInvite(ticket)
            }
            else vm.message("账号已切换，请重新扫描朋友二维码")
        }
    }
    if(state.profile == null) { EmptyPanel("你的洗衣，一目了然", "登录后查看登记、排队和好友代取。", Icons.Outlined.LocalLaundryService, "钉钉登录", login); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { val requested = screen; vm.invite { if(requested === currentScreen) invite = it } }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp)); Text("添加朋友") }
            FilledTonalButton(onClick = { assistant = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Text("洗衣助手") }
        } }
        item { Text("正在洗衣", style = MaterialTheme.typography.titleLarge) }
        if(state.tasks.none { !it.done }) item { Text("还没有进行中的登记，去洗衣房选一台机器吧。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.tasks.filter { !it.done }, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.name, style = MaterialTheme.typography.titleMedium)
                val min = task.endAt?.let { ((it - now + 59999) / 60000).coerceAtLeast(0) }
                Text(if(min == null) "等待设备更新时间" else if(min == 0L) "预计时间已到 · 请确认设备状态" else "预计剩余 $min 分钟", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                if(task.note.isNotBlank()) Text("公开备注：${task.note}")
                Text(listOfNotNull("使用消毒液".takeIf { task.disinfectant }, "已自清洁".takeIf { task.selfCleaned }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { vm.take(task) }, enabled = !state.busy) { Text("我已取衣") }; OutlinedButton(onClick = { pickupFor = task }, enabled = !state.busy) { Text("请 TA 代取") } }
            } }
        }
        if(state.queues.isNotEmpty()) item { Text("洗衣排队", style = MaterialTheme.typography.titleLarge) }
        items(state.queues, key = { "queue." + it.str("id", it.str("machineId")) }) { q -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(q.str("machineName", "机器排队")); Text("预计开始：${q.str("estimatedStartAt", q.str("startAt", "以机器剩余时间为准"))}"); if(q.optBoolean("mine")) TextButton(onClick = { vm.cancelQueue(q.str("machineId")) }, enabled = !state.busy) { Text("取消排队") } } } }
        item { Text("朋友与代取", style = MaterialTheme.typography.titleLarge) }
        val friends = state.social.optJSONArray("friends")?.objects().orEmpty()
        if(friends.isEmpty()) item { Text("扫描朋友的个人二维码绑定，代取请求会同步通知对方。") }
        items(friends, key = { "friend." + it.str("id") }) { friend -> ListItem(headlineContent = { Text(friend.str("nick")) }, leadingContent = { Icon(Icons.Outlined.PersonOutline, null) }, supportingContent = { Text("已绑定的朋友") }) }
        item { OutlinedButton(onClick = { scannerAccount = account; scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 WeShirt 个人二维码").setBeepEnabled(false).setOrientationLocked(false)) }, enabled = !state.busy && scannerAccount == null, modifier = Modifier.fillMaxWidth()) { Text("扫描个人二维码") } }
        val received = state.social.optJSONArray("receivedPickups")?.objects().orEmpty()
        items(received, key = { "pickup.received." + it.str("id") }) { request ->
            val status = request.str("state")
            val task = request.optJSONObject("task")
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${request.optJSONObject("peer")?.str("nick", "朋友")} 请你代取", style = MaterialTheme.typography.titleMedium)
                Text(task?.str("machineName", "机器信息待更新").orEmpty())
                val remaining = task?.str("endAt")?.let(::epoch)?.let { ((it - now + 59_999) / 60_000).coerceAtLeast(0) }
                if(remaining != null) Text(if(remaining > 0) "预计剩余 $remaining 分钟" else "预计时间已到，请确认设备状态")
                when(status) {
                    "pending" -> Row {
                        TextButton(onClick = { vm.respondPickup(request.str("id"), "accept") }, enabled = !state.busy && task?.optBoolean("unavailable") != true) { Text("接受") }
                        TextButton(onClick = { vm.respondPickup(request.str("id"), "reject") }, enabled = !state.busy) { Text("暂不方便") }
                    }
                    "accepted" -> { Text("已接受代取"); TextButton(onClick = { vm.respondPickup(request.str("id"), "complete") }, enabled = !state.busy) { Text("确认已代取") } }
                    "completed" -> Text("已完成代取")
                    "rejected" -> Text("已婉拒")
                    else -> Text("请求已更新，请刷新查看")
                }
            } }
        }
        val sent = state.social.optJSONArray("sentPickups")?.objects().orEmpty()
        items(sent, key = { "pickup.sent." + it.str("id") }) { request ->
            ListItem(headlineContent = { Text("请 ${request.optJSONObject("peer")?.str("nick", "朋友")} 代取") },
                supportingContent = { Text(request.optJSONObject("task")?.str("machineName").orEmpty() + " · " + when(request.str("state")) { "pending" -> "等待接受"; "accepted" -> "对方已接受"; "completed" -> "已完成代取"; "rejected" -> "对方暂不方便"; else -> "状态已更新" }) })
        }
        val threads = state.social.optJSONArray("threads")?.objects().orEmpty()
        if(threads.isNotEmpty()) item { Text("衣物联系", style = MaterialTheme.typography.titleLarge) }
        items(threads, key = { "thread." + it.str("id") }) { t -> Card(onClick = { thread = t }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(t.optJSONObject("peer")?.str("nick").orEmpty()); Text(if(t.str("state") == "accepted") t.str("lastMessage", "双向聊天已开启") else t.str("requestText", "等待对方确认")) } } }
        if(state.tasks.any { it.done }) item { Text("已完成", style = MaterialTheme.typography.titleLarge) }
        items(state.tasks.filter { it.done }.take(10), key = { "done." + it.id }) { task -> ListItem(headlineContent = { Text(task.name) }, supportingContent = { Text("已确认取衣") }, leadingContent = { Icon(Icons.Outlined.CheckCircleOutline, null) }) }
    }
    if(pickupFor != null) AlertDialog(onDismissRequest = { pickupFor = null }, title = { Text("请哪位朋友帮忙？") }, text = { Column { val friends = state.social.optJSONArray("friends")?.objects().orEmpty(); if(friends.isEmpty()) Text("先扫描二维码添加朋友，再发送代取请求。"); friends.forEach { friend -> TextButton(onClick = { vm.pickup(friend.str("id"), pickupFor!!.id); pickupFor = null }, enabled = !state.busy) { Text(friend.str("nick")) } } } }, confirmButton = { TextButton(onClick = { pickupFor = null }) { Text("返回") } })
    if(invite != null) AlertDialog(onDismissRequest = { invite = null }, title = { Text("让朋友扫一扫") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { val bitmap = remember(invite) { qr(invite!!) }; Image(bitmap.asImageBitmap(), "个人邀请二维码", Modifier.fillMaxWidth().aspectRatio(1f)); Text("二维码仅用于朋友邀请，不包含登录凭证。") } }, confirmButton = { TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite), "分享邀请")) }) { Text("分享") } }, dismissButton = { TextButton(onClick = { invite = null }) { Text("关闭") } })
    if(assistant) key(account) { AssistantDialog(state, vm) { assistant = false } }
    thread?.let { key(account, it.str("id")) { PrivateThreadDialog(it, state, vm) { thread = null } } }
}
private fun qr(text: String): Bitmap { val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 600,600); return Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply { val pixels = IntArray(360000) { i -> if(matrix[i % 600, i / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }; setPixels(pixels,0,600,0,0,600,600) } }
@Composable private fun AssistantDialog(state: AppState, vm: AppViewModel, close: () -> Unit) {
    var input by remember { mutableStateOf("") }; var reply by remember { mutableStateOf("可以询问当前楼栋空闲机器、洗衣建议或你的洗衣记录。") }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    AlertDialog(onDismissRequest = close, title = { Text("洗衣助手") }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Text(reply); OutlinedTextField(input, { input = it.take(1000) }, enabled = !state.busy, label = { Text("想问什么？") }, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = {
        val submitted = input
        vm.assistant(submitted) { response -> if(active) { reply = response; if(input == submitted) input = "" } }
    }, enabled = !state.busy && input.isNotBlank()) { Text("发送") } }, dismissButton = { TextButton(onClick = close) { Text("关闭") } })
}
@Composable private fun PrivateThreadDialog(initial: JSONObject, state: AppState, vm: AppViewModel, close: () -> Unit) {
    val account = state.endpoint to state.profile?.id
    val id = initial.str("id")
    var active by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf(initial) }; var text by remember { mutableStateOf("") }
    fun stillCurrent() = active && account.first == vm.state.value.endpoint && account.second != null && account.second == vm.state.value.profile?.id
    DisposableEffect(Unit) { onDispose { active = false } }
    LaunchedEffect(id) {
        try {
            val value = vm.api.call("/api/social/threads/${vm.api.encoded(id)}").optJSONObject("thread") ?: initial
            if(stillCurrent()) current = value
        } catch(cancel: CancellationException) { throw cancel }
        catch(problem: Exception) { if(stillCurrent()) vm.message(problem.message ?: "会话加载失败") }
    }
    AlertDialog(onDismissRequest = close, title = { Text(current.optJSONObject("peer")?.str("nick", "衣物联系") ?: "衣物联系") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if(current.str("state") != "accepted") Text(when(current.str("state")) { "rejected" -> "此联系请求已拒绝"; "blocked" -> "会话已屏蔽"; else -> current.str("requestText", "对方确认后开启完整聊天") })
        else { current.optJSONArray("messages")?.objects()?.takeLast(10)?.forEach { Text((if(it.str("sender") == "me") "我：" else "对方：") + it.str("text")) }; OutlinedTextField(text, { text = it.take(500) }, enabled = !state.busy, label = { Text("消息") }) }
    } }, confirmButton = { TextButton(onClick = {
        val submitted = text
        val accepted = current.str("state") == "accepted"
        vm.mutate(null) {
            if(!stillCurrent()) throw CancellationException("Account changed")
            val result = if(accepted) vm.api.call("/api/social/threads/${vm.api.encoded(id)}/messages", "POST", JSONObject().put("text", submitted))
                else vm.api.call("/api/social/threads/${vm.api.encoded(id)}/accept", "POST", JSONObject().put("action", "accept"))
            if(stillCurrent()) {
                if(text == submitted) text = ""
                result.optJSONObject("thread")?.let { current = it }
            }
            val updated = vm.api.call("/api/social/threads/${vm.api.encoded(id)}").optJSONObject("thread")
            if(stillCurrent() && updated != null) current = updated
        }
    }, enabled = !state.busy && (current.str("state") == "accepted" && text.isNotBlank() || current.str("state") == "pending" && current.optBoolean("incoming"))) { Text(if(current.str("state") == "accepted") "发送" else "同意聊天") } }, dismissButton = {
        Row {
            if(current.str("state") == "pending" && current.optBoolean("incoming")) TextButton(onClick = {
                vm.mutate(null) {
                    if(!stillCurrent()) throw CancellationException("Account changed")
                    val result = vm.api.call("/api/social/threads/${vm.api.encoded(id)}/accept", "POST", JSONObject().put("action", "reject"))
                    if(stillCurrent()) { result.optJSONObject("thread")?.let { current = it }; close() }
                }
            }, enabled = !state.busy) { Text("拒绝") }
            TextButton(onClick = close) { Text("关闭") }
        }
    })
}
