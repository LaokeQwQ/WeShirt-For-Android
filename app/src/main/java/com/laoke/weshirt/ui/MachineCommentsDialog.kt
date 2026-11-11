package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private data class DiscussionScope(val endpoint: String, val userId: String?, val building: String, val machineId: String) {
    fun matches(state: AppState) = userId != null && endpoint == state.endpoint && userId == state.profile?.id && building == state.building
}

@Composable
internal fun MachineCommentsDialog(machine: Machine, state: AppState, vm: AppViewModel, close: () -> Unit) {
    val scope = DiscussionScope(state.endpoint, state.profile?.id, state.building, machine.id)
    val currentScope by rememberUpdatedState(scope)
    val repository = remember(vm.api) { MachineDiscussion(vm.api) }
    val coroutine = rememberCoroutineScope()
    var comments by remember(scope) { mutableStateOf<List<Message>>(emptyList()) }
    var input by remember(scope) { mutableStateOf("") }
    var reply by remember(scope) { mutableStateOf<Message?>(null) }
    var loading by remember(scope) { mutableStateOf(true) }
    var sending by remember(scope) { mutableStateOf(false) }
    var muted by remember(scope) { mutableStateOf(false) }
    var accessDenied by remember(scope) { mutableStateOf(false) }
    var error by remember(scope) { mutableStateOf<String?>(null) }
    var page by remember(scope) { mutableIntStateOf(1) }
    var hasMore by remember(scope) { mutableStateOf(false) }
    var refreshKey by remember(scope) { mutableIntStateOf(0) }
    fun active() = scope == currentScope && scope.matches(vm.state.value)
    fun failure(problem: Exception) = if (problem is ApiException) problem.message else
        "留言请求未完成，请重试\n状态码 0 · 事件 client_" + UUID.randomUUID().toString().replace("-", "")
    LaunchedEffect(scope, page, refreshKey) {
        loading = true; error = null
        try {
            if (scope.building.isBlank()) error = "请先选择住宿楼"
            else if (scope.userId == null) error = "登录后可查看自己楼栋的机器留言"
            else if (active()) {
                val result = repository.load(scope.building, machine.id, page)
                if (active()) {
                    comments = if (page == 1) result.messages else (comments + result.messages).distinctBy { it.id }
                    muted = result.muted; hasMore = result.hasMore
                    accessDenied = false
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: Exception) { if (active()) { error = failure(problem); if (problem is ApiException && problem.status in setOf(401, 403)) { accessDenied = true; comments = emptyList() } } }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("${machine.name} · 留言") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("留言在本楼栋频道公开，和 Web 同步。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (comments.isEmpty() && !loading && error == null) item { Text("还没有留言，来分享这台机器的使用情况吧。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(comments, key = { it.id }) { comment ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(listOf(comment.nick, comment.building).filter(String::isNotBlank).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    if (comment.role.isNotBlank() && comment.role != "用户") Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(comment.role, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall) }
                                }
                                if (comment.pinned) Text("置顶", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                if (comment.reply.isNotBlank()) Text(comment.reply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (comment.text.isNotBlank()) Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                                if (comment.mediaId.isNotBlank()) Text(if (comment.mediaKind == "video") "视频留言 · 在楼栋频道查看" else "图片留言 · 在楼栋频道查看", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { reply = comment }, enabled = !sending && !muted && !accessDenied) { Text("回复") }
                                    TextButton(onClick = { vm.report(comment) }, enabled = !state.busy) { Text("举报") }
                                }
                            }
                        }
                    }
                    if (hasMore) item { TextButton(onClick = { page++ }, enabled = !loading && page < 100) { Text("查看更多留言") } }
                }
                if (loading) Text("正在加载留言…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (muted) Text("你当前被禁言，暂时不能留言或回复。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (reply != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("回复 ${reply!!.nick}：${reply!!.text.take(30)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { reply = null }, enabled = !sending) { Text("取消回复") }
                }
                OutlinedTextField(input, { input = it.take(200) }, label = { Text("给这台机器留言") }, enabled = !sending && !muted && !accessDenied,
                    supportingText = { Text("${input.length}/200 · 请勿填写手机号等个人信息") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { page = 1; refreshKey++ }, enabled = !loading && !sending) { Text("刷新留言") }
                    TextButton(onClick = { vm.channel(scope.building); vm.tab(2); close() }, enabled = !sending) { Text("进入楼栋频道") }
                }
            }
        },
        dismissButton = { TextButton(onClick = close) { Text("关闭") } },
        confirmButton = {
            TextButton(enabled = input.trim().isNotEmpty() && !sending && !loading && !muted && !accessDenied && scope.userId != null, onClick = {
                if (!active() || sending) return@TextButton
                val text = input.trim(); val replyId = reply?.id
                sending = true; error = null
                coroutine.launch {
                    try {
                        if (!active()) throw CancellationException("Account or building changed")
                        repository.post(scope.building, machine.id, text, replyId)
                        if (active()) {
                            if (input.trim() == text && reply?.id == replyId) { input = ""; reply = null }
                            page = 1; refreshKey++; vm.message("留言已发布")
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (problem: Exception) { if (active()) { error = failure(problem); if (problem is ApiException) { if (problem.code == "muted") muted = true; if (problem.status in setOf(401, 403)) accessDenied = true } } }
                    finally { if (scope == currentScope) sending = false }
                }
            }) { Text(if (sending) "正在发布…" else "发布留言") }
        }
    )
}
