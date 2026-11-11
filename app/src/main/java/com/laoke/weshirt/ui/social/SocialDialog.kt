package com.laoke.weshirt.ui.social

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import com.laoke.weshirt.ui.user.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject

private data class PickupConfirmation(val title: String, val message: String, val action: () -> Unit)

@Composable fun SocialDialog(state: AppState, vm: AppViewModel, close: () -> Unit, initialPickupTaskId: String? = null, initialInvite: String? = null) {
    val session = rememberUserFeatureSession(state, vm)
    var friends by remember(session) { mutableStateOf(state.social.optJSONArray("friends")?.objects().orEmpty()) }
    var threads by remember(session) { mutableStateOf(state.social.optJSONArray("threads")?.objects().orEmpty()) }
    var received by remember(session) { mutableStateOf(state.social.optJSONArray("receivedPickups")?.objects().orEmpty()) }
    var sent by remember(session) { mutableStateOf(state.social.optJSONArray("sentPickups")?.objects().orEmpty()) }
    var invite by remember(session) { mutableStateOf<Pair<String, String>?>(null) }
    var inviteInput by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf("") }
    var previewTicket by remember(session) { mutableStateOf<String?>(null) }
    var preview by remember(session) { mutableStateOf<JSONObject?>(null) }
    var contact by remember(session) { mutableStateOf<String?>(null) }
    var activeThread by remember(session) { mutableStateOf<JSONObject?>(null) }
    var confirmation by remember(session) { mutableStateOf<PickupConfirmation?>(null) }
    var scannerSession by remember { mutableStateOf<UserFeatureSession?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current

    suspend fun UserFeatureSession.readSocial() {
        val data = coroutineScope {
            val dashboard = async { request("/api/social") }
            val requests = async { request("/api/social/pickups") }
            dashboard.await() to requests.await()
        }
        current()
        friends = data.first.optJSONArray("friends")?.objects().orEmpty()
        threads = data.first.optJSONArray("threads")?.objects().orEmpty()
        received = data.second.optJSONArray("received")?.objects().orEmpty()
        sent = data.second.optJSONArray("sent")?.objects().orEmpty()
    }
    fun load(silent: Boolean = false) { session.run(silent = silent) { readSocial() } }
    fun previewInvite(raw: String) {
        val ticket = ShareLinks.ticket(raw, "ticket")
        if (ticket == null) { session.error = "邀请链接格式不正确，请扫描或粘贴完整的 WeShirt 邀请"; return }
        session.run {
            val data = request("/api/social/invite?ticket=${vm.api.encoded(ticket)}", auth = false)
            preview = data; previewTicket = ticket
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val launched = scannerSession
        scannerSession = null
        if (result.contents != null) {
            if (launched === session) previewInvite(result.contents) else vm.message("账号已切换，请重新扫描朋友二维码")
        }
    }
    fun makeInvite(kind: String) { session.run {
        val data = request("/api/social/invite", "POST", JSONObject().put("kind", kind))
        invite = (if (kind == "friend") "扫码添加好友" else "分享 WeShirt") to ShareLinks.social(state.endpoint, data.str("ticket"))
    } }
    fun respond(entry: JSONObject, action: String) {
        fun execute() { session.run(refresh = true) {
            request("/api/social/pickups/${vm.api.encoded(entry.str("id"))}/respond", "POST", JSONObject().put("action", action))
            readSocial()
            vm.message(when (action) { "complete" -> "已确认代取，请与好友交接"; "accept" -> "已接受代取"; else -> "已告知好友" })
        } }
        if (action == "complete") confirmation = PickupConfirmation("确认已取到衣物？", "确认已从「${entry.optJSONObject("task")?.str("machineName", "这台机器")}」取走衣物后继续，对应登记将标记为已取。", ::execute)
        else execute()
    }
    LaunchedEffect(session, initialInvite) {
        // Resolve the invitation first. No friendship is created until the user confirms.
        if (!initialInvite.isNullOrBlank()) previewInvite(initialInvite) else load()
    }
    LaunchedEffect(session, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(30_000); now = System.currentTimeMillis(); if (contact == null && activeThread == null && invite == null && preview == null) load(true) }
        }
    }
    UserFeatureDialog("朋友与代取", close, actions = { IconButton(onClick = { load() }, enabled = !session.busy) { Icon(Icons.Outlined.Refresh, "刷新协作信息") } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { FeatureError(session) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !session.busy, onClick = { makeInvite("friend") }) { Text("我的二维码") }
                        OutlinedButton(enabled = !session.busy, onClick = { makeInvite("share") }) { Text("分享看板") }
                        OutlinedButton(enabled = !session.busy && scannerSession == null, onClick = { scannerSession = session; scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 WeShirt 个人二维码").setBeepEnabled(false).setOrientationLocked(false)) }) { Text("扫码添加") }
                    }
                    OutlinedTextField(inviteInput, { inviteInput = it.take(12_000) }, label = { Text("邀请链接或扫码内容") }, maxLines = 3, enabled = !session.busy, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !session.busy && inviteInput.isNotBlank(), onClick = { previewInvite(inviteInput) }) { Text("打开邀请") }
                    Text("扫码后需确认添加好友；分享看板不会自动添加好友。邀请链接 24 小时有效。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (initialPickupTaskId != null) {
                val task = state.tasks.find { it.id == initialPickupTaskId && !it.done && !it.isArchived(now) && !it.isExpired(now) }
                item { Text("请好友代取 · ${task?.name ?: "登记已更新"}", style = MaterialTheme.typography.titleMedium) }
                if (task != null) {
                    item { Text("选择方便帮忙的好友，确认后会通知对方。请等机器结束后再取衣。", style = MaterialTheme.typography.bodySmall) }
                    if (friends.none { !it.optBoolean("blocked") }) item { Text("还没有可邀请的好友，先扫码添加朋友。") }
                    items(friends.filterNot { it.optBoolean("blocked") }, key = { "pickup.friend.${it.str("id")}" }) { friend ->
                        OutlinedButton(enabled = !session.busy, onClick = {
                            confirmation = PickupConfirmation("请 ${friend.str("nick")} 代取？", "机器：${task.name}\n会发送通知，等待好友接受后再协作取衣。") {
                                session.run(refresh = true) {
                                    val response = request("/api/social/pickup", "POST", JSONObject().put("friendId", friend.str("id")).put("taskId", task.id))
                                    readSocial()
                                    vm.message(if (response.optBoolean("notified")) "代取请求已发送" else "请求已保存，好友可在站内查看")
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("请 ${friend.str("nick")} 帮忙") }
                    }
                }
            }
            item { Text("我的好友", style = MaterialTheme.typography.titleLarge) }
            if (friends.isEmpty()) item { Text("还没有好友，把你的个人二维码分享给室友吧。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(friends, key = { "friend.${it.str("id")}" }) { friend ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureAvatar(friend.str("nick", "朋友"), friend.str("avatarUrl"), state)
                    Column(Modifier.weight(1f)) { Text(friend.str("nick", "朋友"), style = MaterialTheme.typography.titleMedium); if (friend.optBoolean("blocked")) Text("已屏蔽", style = MaterialTheme.typography.bodySmall) }
                    if (!friend.optBoolean("blocked")) TextButton(onClick = { contact = friend.str("id") }, enabled = !session.busy) { Text("联系 TA") }
                } }
            }
            item { Text("衣物联系", style = MaterialTheme.typography.titleLarge) }
            if (threads.isEmpty()) item { Text("暂时没有会话。联系使用者时先发送固定说明，对方接受后开启双向聊天。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(threads, key = { "thread.${it.str("id")}" }) { thread ->
                Card(onClick = { activeThread = thread }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val peer = thread.optJSONObject("peer")
                            FeatureAvatar(peer?.str("nick", "同学").orEmpty(), peer?.str("avatarUrl").orEmpty(), state)
                            Text(peer?.str("nick", "同学").orEmpty(), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(when (thread.str("state")) { "pending" -> if (thread.optBoolean("incoming")) "等待你的确认" else "等待对方确认"; "accepted" -> "可聊天"; "rejected" -> "已拒绝"; "blocked" -> "已屏蔽"; else -> "状态待刷新" }, style = MaterialTheme.typography.labelMedium, color = if (thread.str("state") == "pending" && thread.optBoolean("incoming")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(thread.str("lastMessage").ifBlank { thread.str("requestText") }, maxLines = 3)
                    }
                }
            }
            item { Text("收到的代取请求", style = MaterialTheme.typography.titleLarge) }
            if (received.isEmpty()) item { Text("暂时没有收到的请求。") }
            items(received, key = { "received.${it.str("id")}" }) { request -> PickupCard(request, now, session.busy, true, ::respond) }
            item { Text("发出的代取请求", style = MaterialTheme.typography.titleLarge) }
            if (sent.isEmpty()) item { Text("暂时没有发出的请求。在「我的洗衣」选择未取任务，即可请好友帮忙。") }
            items(sent, key = { "sent.${it.str("id")}" }) { request -> PickupCard(request, now, session.busy, false, ::respond) }
        }
    }
    preview?.let { data -> AlertDialog(onDismissRequest = { if (!session.busy) { preview = null; previewTicket = null } }, title = { Text(if (data.str("kind") == "share") "一起使用 WeShirt" else "确认添加好友") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("${data.str("nick", "同学")}${if (data.str("kind") == "share") " 向你分享了洗衣房看板。" else " 邀请你成为好友，方便互相代取衣物。"}"); FeatureError(session) } }, dismissButton = { TextButton(enabled = !session.busy, onClick = { preview = null; previewTicket = null }) { Text("取消") } }, confirmButton = {
        TextButton(enabled = !session.busy && previewTicket != null, onClick = {
            if (data.str("kind") == "share") { preview = null; previewTicket = null; vm.tab(0); close() }
            else { val ticket = previewTicket; session.run(refresh = true) {
                val accepted = request("/api/social/accept", "POST", JSONObject().put("ticket", ticket))
                preview = null; previewTicket = null; inviteInput = ""
                readSocial(); vm.message(if (accepted.optBoolean("added")) "已添加好友" else "你们已经是好友了")
            } }
        }) { Text(if (data.str("kind") == "share") "去看板" else "确认添加好友") }
    }) }
    invite?.let { (title, url) -> ShareCodeDialog(title, url, "邀请 24 小时有效；二维码只包含邀请凭证，不包含登录信息。") { invite = null } }
    contact?.let { toUserId -> key(state.endpoint, state.profile?.id, toUserId) {
        // Use freshly loaded threads even when the application dashboard has not refreshed yet.
        ContactUserDialog(toUserId, null, state.copy(social = JSONObject(state.social.toString()).put("threads", org.json.JSONArray(threads))), vm) { contact = null; load() }
    } }
    activeThread?.let { current -> key(state.endpoint, state.profile?.id, current.str("id")) { PrivateThreadDialog(current, state, vm) { activeThread = null; load() } } }
    confirmation?.let { ask -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(ask.title) }, text = { Text(ask.message) }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }, confirmButton = { TextButton(enabled = !session.busy, onClick = { confirmation = null; ask.action() }) { Text("确认") } }) }
}

@Composable private fun PickupCard(request: JSONObject, now: Long, busy: Boolean, received: Boolean, respond: (JSONObject, String) -> Unit) {
    val task = request.optJSONObject("task")
    val state = request.str("state")
    val unavailable = task == null || task.optBoolean("unavailable")
    val done = task?.optBoolean("done") == true
    val endAt = task?.str("endAt")?.let(::epoch)
    val left = endAt?.let { ((it - now + 59_999) / 60_000).coerceAtLeast(0) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (received) "${request.optJSONObject("peer")?.str("nick", "朋友")} 请你代取" else "请 ${request.optJSONObject("peer")?.str("nick", "朋友")} 代取", style = MaterialTheme.typography.titleMedium)
        Text(task?.str("machineName", "任务暂不可用").orEmpty())
        Text(when { unavailable -> "任务已删除或暂不可用"; done -> "已确认取衣"; left == null -> "结束时间待确认，请以现场为准"; left > 0 -> "预计剩余 $left 分钟，请等机器结束后取衣"; else -> "计时已结束，请现场确认后再取衣" }, style = MaterialTheme.typography.bodySmall)
        Text(when (state) { "pending" -> "等待接受"; "accepted" -> "已接受代取"; "completed" -> "已完成代取"; "rejected" -> "暂不方便"; else -> "状态已更新" }, color = MaterialTheme.colorScheme.primary)
        if (received && state == "pending") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && !unavailable && !done, onClick = { respond(request, "accept") }) { Text("接受") }
            TextButton(enabled = !busy, onClick = { respond(request, "reject") }) { Text("不方便") }
        }
        if (received && state == "accepted" && !unavailable && !done) Button(enabled = !busy, onClick = { respond(request, "complete") }) { Text("已帮忙取到") }
    } }
}
