package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.LaundryTask
import com.laoke.weshirt.ui.user.FeatureError
import com.laoke.weshirt.ui.user.rememberUserFeatureSession

/** Match the Web opening prompt: the latest expired registration within three hours. */
internal fun overdueCollectionCandidate(tasks: List<LaundryTask>, now: Long): LaundryTask? = tasks
    .filter { !it.done && it.endAt?.let { end -> (now - end) / 60_000 in 1L..180L } == true }
    .maxByOrNull { it.endAt ?: Long.MIN_VALUE }

@Composable
fun OverdueCollectionPrompt(state: AppState, vm: AppViewModel, enabled: Boolean = true) {
    val session = rememberUserFeatureSession(state, vm)
    var checked by remember(session) { mutableStateOf(false) }
    var selected by remember(session) { mutableStateOf<LaundryTask?>(null) }
    val permissions = state.access.optJSONObject("permissions")?.optJSONObject("tasks")
    val allowed = state.profile != null && permissions?.optBoolean("view", true) != false && permissions?.optBoolean("operate", true) != false
    val mayPrompt = enabled && allowed && !state.loading && state.tab != 1 && state.announcements.isEmpty()

    LaunchedEffect(session, mayPrompt, state.tasks) {
        if (!checked && mayPrompt) {
            checked = true
            selected = overdueCollectionCandidate(state.tasks, System.currentTimeMillis())
        }
        if (!allowed || selected?.let { task -> state.tasks.none { it.id == task.id && !it.done } } == true) selected = null
    }
    selected?.takeIf { enabled && allowed && state.announcements.isEmpty() }?.let { task ->
        val overdue = ((System.currentTimeMillis() - (task.endAt ?: 0)) / 60_000).coerceAtLeast(1)
        val whenText = if (overdue < 60) "$overdue 分钟前" else "${overdue / 60} 小时前"
        AlertDialog(
            onDismissRequest = { if (!session.busy) selected = null },
            title = { Text("衣服取到了吗？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${task.name.ifBlank { task.machineId }} 的登记计时在${whenText}结束。计时到点不表示机器已经结束，请确认机器和衣物的现场情况。")
                FeatureError(session)
            } },
            dismissButton = { TextButton(enabled = !session.busy, onClick = { selected = null }) { Text("还没取") } },
            confirmButton = { TextButton(enabled = !session.busy && allowed, onClick = {
                session.run(refresh = true) {
                    val currentTask = vm.state.value.tasks.find { it.id == task.id }
                    if (currentTask == null || currentTask.done) throw IllegalStateException("登记已更新，请刷新后确认")
                    if (currentTask.endAt?.let { it <= System.currentTimeMillis() } != true) throw IllegalStateException("登记计时已变化，请先核对机器")
                    request("/api/tasks/${vm.api.encoded(task.id)}/take", "POST")
                    selected = null
                    vm.message("已确认取衣")
                }
            }) { Text(if (session.busy) "正在确认…" else "我已取衣") } }
        )
    }
}
