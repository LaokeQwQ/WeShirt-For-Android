package com.laoke.weshirt.ui

import androidx.compose.runtime.*
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.LaundryTask
import com.laoke.weshirt.data.deviceStillRunning
import com.laoke.weshirt.data.isArchived
import com.laoke.weshirt.data.isExpired
import com.laoke.weshirt.data.laundryDisplayTask

/** Match the Web opening prompt: the latest expired registration within three hours. */
internal fun overdueCollectionCandidate(tasks: List<LaundryTask>, now: Long): LaundryTask? = tasks
    .filter { !it.done && !it.deviceStillRunning(now) && !it.isArchived(now) && !it.isExpired(now) &&
        it.endAt?.let { end -> (now + it.clockOffsetMs - end) / 60_000 in 1L..180L } == true }
    .maxByOrNull { it.endAt ?: Long.MIN_VALUE }

@Composable
fun OverdueCollectionPrompt(state: AppState, vm: AppViewModel, enabled: Boolean = true) {
    var checked by remember(vm, state.endpoint, state.profile?.id) { mutableStateOf(false) }
    var selected by remember(vm, state.endpoint, state.profile?.id) { mutableStateOf<LaundryTask?>(null) }
    val permissions = state.access.optJSONObject("permissions")?.optJSONObject("tasks")
    val allowed = state.profile != null && permissions?.optBoolean("view", true) != false && permissions?.optBoolean("operate", true) != false
    val mayPrompt = enabled && allowed && state.authReady && !state.loading && state.tab != 1 && state.announcements.isEmpty()

    LaunchedEffect(vm, state.endpoint, state.profile?.id, mayPrompt, state.tasks, state.machines, state.boardFromCache) {
        val now = System.currentTimeMillis()
        if (!checked && mayPrompt) {
            checked = true
            selected = overdueCollectionCandidate(state.tasks.map { state.laundryDisplayTask(it, now) }, now)
        }
        selected?.let { task ->
            val updated = state.tasks.find { it.id == task.id }?.let { state.laundryDisplayTask(it, now) }
            if (!allowed || updated == null || overdueCollectionCandidate(listOf(updated), now) == null) selected = null
            else selected = updated
        }
    }
    selected?.takeIf { enabled && allowed && state.announcements.isEmpty() }?.let { task -> key(state.endpoint, state.profile?.id, task.id) {
        val overdue = ((System.currentTimeMillis() + task.clockOffsetMs - (task.endAt ?: 0)) / 60_000).coerceAtLeast(1)
        val whenText = if (overdue < 60) "$overdue 分钟前" else "${overdue / 60} 小时前"
        TaskTakeDialog(
            title = "衣服取到了吗？",
            detail = "${task.name.ifBlank { task.machineId }} 的登记计时在${whenText}结束。请到现场确认机器已结束、衣物已取走。",
            cancelLabel = "还没取",
            confirmLabel = "我已取衣",
            busy = state.busy,
            close = { selected = null },
        ) { succeeded, failed -> vm.take(task.id, requireElapsed = true, onSuccess = succeeded, onFailure = failed) }
    } }
}
