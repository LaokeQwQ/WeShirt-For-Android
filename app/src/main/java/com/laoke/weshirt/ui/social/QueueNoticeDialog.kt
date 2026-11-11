package com.laoke.weshirt.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import com.laoke.weshirt.ui.user.*
import org.json.JSONObject

@Composable fun QueueNoticeDialog(state: AppState, vm: AppViewModel, close: () -> Unit) {
    val session = rememberUserFeatureSession(state, vm)
    var notices by remember(session) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loaded by remember(session) { mutableStateOf(false) }
    fun load() { session.run { notices = request("/api/queue/notices").optJSONArray("notices")?.objects().orEmpty().take(30); loaded = true } }
    LaunchedEffect(session) { load() }
    val unread = notices.count { it.optBoolean("active") && it.str("readAt").isBlank() }
    UserFeatureDialog("预约提醒 · $unread 条未读", close, actions = { IconButton(onClick = ::load, enabled = !session.busy) { Icon(Icons.Outlined.Refresh, "刷新预约提醒") } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("只提醒当前队首。到场后请确认机器与上一位的衣物。", style = MaterialTheme.typography.bodySmall); FeatureError(session) }
            if (loaded && notices.isEmpty()) item { Text("暂时没有预约提醒。") }
            items(notices, key = { it.str("id") }) { notice ->
                val active = notice.optBoolean("active")
                val read = notice.str("readAt").isNotBlank()
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (active && !read) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(notice.str("title", "预约提醒"), style = MaterialTheme.typography.titleMedium)
                        epoch(notice.str("at"))?.let { time -> Text(java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")), style = MaterialTheme.typography.labelSmall) }
                        Text(notice.str("text"))
                        if (!active) Text("预约已结束或已取消", style = MaterialTheme.typography.bodySmall)
                        if (!read) TextButton(enabled = !session.busy, onClick = { session.run {
                            request("/api/queue/notices/${vm.api.encoded(notice.str("id"))}/read", "POST")
                            notices = notices.map { if (it.str("id") == notice.str("id")) JSONObject(it.toString()).put("readAt", java.time.Instant.now().toString()) else it }
                        } }) { Text("标为已读") }
                        TextButton(enabled = !session.busy, onClick = { session.run {
                            if (!read) request("/api/queue/notices/${vm.api.encoded(notice.str("id"))}/read", "POST")
                            vm.tab(1)
                            close()
                        } }) { Text("查看我的预约") }
                    }
                }
            }
        }
    }
}
