package com.laoke.weshirt.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import com.laoke.weshirt.ui.user.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val contactTemplates = listOf(
    "forgot" to "衣物还在机器里，提醒来取",
    "misplaced" to "衣物可能放错位置，想核对",
    "taken_wrong" to "衣物可能被误取，想核对",
    "left_behind" to "发现遗留物品，提醒确认"
)

@Composable fun ContactUserDialog(toUserId: String, machineId: String?, state: AppState, vm: AppViewModel, close: () -> Unit) {
    val existing = state.social.optJSONArray("threads")?.objects()?.find { it.optJSONObject("peer")?.str("id") == toUserId }
    ClothingThreadDialog(existing?.str("id"), toUserId, machineId, existing, state, vm, close)
}

@Composable internal fun PrivateThreadDialog(thread: JSONObject, state: AppState, vm: AppViewModel, close: () -> Unit) {
    ClothingThreadDialog(thread.str("id"), thread.optJSONObject("peer")?.str("id").orEmpty(), null, thread, state, vm, close)
}

private fun messageTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("")

@Composable private fun ClothingThreadDialog(initialId: String?, toUserId: String, machineId: String?, initial: JSONObject?, state: AppState, vm: AppViewModel, close: () -> Unit) {
    val session = rememberUserFeatureSession(state, vm)
    var thread by remember(session, initialId, toUserId) { mutableStateOf(initial) }
    var draft by rememberSaveable(state.endpoint, state.profile?.id, initialId, toUserId) { mutableStateOf("") }
    var blockConfirm by remember(session) { mutableStateOf(false) }
    var selectedTemplate by remember(session) { mutableStateOf<Pair<String, String>?>(null) }
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val id = thread?.str("id") ?: initialId.orEmpty()
    val currentId by rememberUpdatedState(id)
    val threadState = thread?.str("state")
    fun reload(silent: Boolean = false) {
        val selectedId = currentId
        if (selectedId.isNotBlank()) session.run(silent = silent) {
            thread = request("/api/social/threads/${vm.api.encoded(selectedId)}").optJSONObject("thread") ?: throw IllegalStateException("会话读取结果不完整，请重试")
        }
    }
    LaunchedEffect(session, initialId) { reload() }
    LaunchedEffect(session, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(8_000); if (currentId.isNotBlank()) reload(silent = true) }
        }
    }
    UserFeatureDialog(thread?.optJSONObject("peer")?.str("nick", "衣物联系") ?: "联系使用者", close, actions = {
        IconButton(onClick = { reload() }, enabled = !session.busy && id.isNotBlank()) { Icon(Icons.Outlined.Refresh, "刷新会话") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { FeatureError(session) }
            if (thread == null && !initialId.isNullOrBlank()) item { Text(if (session.busy) "正在读取会话…" else "会话未能读取，请点击右上角重试。") }
            else if (thread == null) {
                item { Text("先发送一条固定说明，对方接受后才开启双向聊天。24 小时内仅一条待确认请求。", style = MaterialTheme.typography.bodyMedium) }
                items(contactTemplates, key = { it.first }) { template -> OutlinedButton(onClick = { selectedTemplate = template }, enabled = !session.busy && toUserId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(template.second) } }
            }
            thread?.let { current ->
                item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.str("requestText"))
                        Text(when (threadState) {
                            "pending" -> if (current.optBoolean("incoming")) "接受后，对方才能发送自由消息；也可以拒绝或屏蔽。" else "请求已发出，等待对方确认。现在还不能自由聊天。"
                            "accepted" -> "已开启双向聊天"
                            "rejected" -> "此联系请求已拒绝，请以现场核对为准。"
                            "blocked" -> "此会话已屏蔽，双方不能继续聊天或邀请代取。"
                            else -> "会话状态已更新，请刷新后再操作。"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (threadState == "pending" && current.optBoolean("incoming")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("accept" to "接受并聊天", "reject" to "拒绝").forEach { (action, label) ->
                                OutlinedButton(enabled = !session.busy, onClick = { session.run(refresh = true) {
                                    thread = request("/api/social/threads/${vm.api.encoded(id)}/accept", "POST", JSONObject().put("action", action)).optJSONObject("thread") ?: throw IllegalStateException("会话状态读取失败，请刷新")
                                } }) { Text(label) }
                            }
                        }
                    } }
                }
                if (threadState == "accepted") {
                    val history = current.optJSONArray("messages")?.objects().orEmpty()
                    if (history.isEmpty()) item { Text("会话已开启，可以核对衣物信息了。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(history, key = { it.str("id") }) { message ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (message.str("sender") == "me") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text((if (message.str("sender") == "me") "我" else current.optJSONObject("peer")?.str("nick", "对方").orEmpty()) + " · " + messageTime(message.str("at")), style = MaterialTheme.typography.labelSmall)
                                Text(message.str("text"))
                                TextButton(onClick = { copyFeatureText(context, message.str("text")); vm.message("已复制") }) { Text("复制") }
                            }
                        }
                    }
                    item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft, { draft = it.take(500) }, label = { Text("输入消息") }, enabled = !session.busy, minLines = 2, maxLines = 5, supportingText = { Text("${draft.length}/500") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !session.busy && draft.isNotBlank(), onClick = {
                            val submitted = draft.trim()
                            session.run(refresh = true) {
                                val message = request("/api/social/threads/${vm.api.encoded(id)}/messages", "POST", JSONObject().put("text", submitted)).optJSONObject("message") ?: throw IllegalStateException("消息发送结果不完整，请刷新后确认")
                                val historyNow = thread?.optJSONArray("messages")?.objects().orEmpty()
                                thread = JSONObject(thread.toString()).put("messages", JSONArray((historyNow + message).takeLast(100)))
                                if (draft.trim() == submitted) draft = ""
                            }
                        }) { Text(if (session.busy) "请稍候…" else "发送") }
                    } }
                }
                if (threadState != "blocked") item { TextButton(enabled = !session.busy, onClick = { blockConfirm = true }) { Text("屏蔽此人", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    selectedTemplate?.let { template -> AlertDialog(onDismissRequest = { selectedTemplate = null }, title = { Text("发送衣物联系请求？") }, text = { Text(template.second + "\n对方接受后才开启双向聊天。") }, dismissButton = { TextButton(onClick = { selectedTemplate = null }) { Text("取消") } }, confirmButton = {
        TextButton(enabled = !session.busy, onClick = {
            selectedTemplate = null
            session.run(refresh = true) {
                val body = JSONObject().put("toUserId", toUserId).put("template", template.first).apply { if (!machineId.isNullOrBlank()) put("machineId", machineId) }
                val response = request("/api/social/contact", "POST", body)
                thread = response.optJSONObject("thread") ?: throw IllegalStateException("会话结果不完整，请刷新确认")
                vm.message(if (response.optBoolean("existing")) "已打开现有会话" else if (response.optBoolean("notified")) "请求已发送" else "请求已保存，对方可在站内查看")
                if (thread?.str("state") == "accepted") thread = request("/api/social/threads/${vm.api.encoded(thread!!.str("id"))}").optJSONObject("thread") ?: thread
            }
        }) { Text("确认发送") }
    }) }
    if (blockConfirm) AlertDialog(onDismissRequest = { blockConfirm = false }, title = { Text("屏蔽此人？") }, text = { Text("屏蔽后，双方不能再通过协作功能聊天或邀请代取。") }, dismissButton = { TextButton(onClick = { blockConfirm = false }) { Text("取消") } }, confirmButton = {
        TextButton(enabled = !session.busy, onClick = { blockConfirm = false; session.run(refresh = true) {
            thread = request("/api/social/threads/${vm.api.encoded(id)}/block", "POST").optJSONObject("thread") ?: throw IllegalStateException("会话状态读取失败，请刷新")
        } }) { Text("确认屏蔽") }
    })
}
