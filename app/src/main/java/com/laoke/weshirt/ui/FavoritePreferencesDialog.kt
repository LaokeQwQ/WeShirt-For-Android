package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private data class ReminderWindow(val start: String = "19:00", val end: String = "22:00", val days: Set<Int> = (1..7).toSet())

@Composable
fun FavoritePreferencesDialog(state: AppState, vm: AppViewModel, close: () -> Unit) {
    val account = state.endpoint + "|" + state.profile?.id
    key(account) {
        var loaded by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var enabled by remember { mutableStateOf(false) }
        var favorites by remember { mutableStateOf(setOf<String>()) }
        var machines by remember { mutableStateOf(emptyList<JSONObject>()) }
        var windows by remember { mutableStateOf(emptyList<ReminderWindow>()) }
        var saved by remember { mutableStateOf(false) }
        fun current() = vm.state.value.let { it.endpoint + "|" + it.profile?.id == account && it.profile != null }
        LaunchedEffect(account) {
            try {
                val response = vm.api.call("/api/user/laundry-preferences")
                if (!current()) return@LaunchedEffect
                val prefs = response.optJSONObject("preferences") ?: JSONObject()
                val ids = prefs.optJSONArray("favorites") ?: JSONArray()
                favorites = (0 until ids.length()).map { ids.optString(it) }.toSet()
                enabled = prefs.optBoolean("enabled")
                machines = response.optJSONArray("machines")?.objects().orEmpty()
                windows = prefs.optJSONArray("timeWindows")?.objects().orEmpty().map { w -> val d = w.optJSONArray("days") ?: JSONArray(); ReminderWindow(w.str("start"), w.str("end"), (0 until d.length()).map { d.optInt(it) }.toSet()) }
                loaded = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (problem: Exception) { error = clientFailure(problem, "偏好读取失败，请重新打开") }
        }
        AlertDialog(onDismissRequest = { if (!state.busy) close() }, title = { Text("常用机器与洗衣提醒") }, text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在常洗时间段发现收藏机器空闲时，每半小时提醒一次，最多三次；登记洗衣后停止。", style = MaterialTheme.typography.bodySmall)
                if (!loaded && error == null) Text("正在读取偏好…")
                machines.filter { it.str("id") in favorites }.forEach { machine -> Row {
                    Text(machine.str("displayName", machine.str("name")), Modifier.weight(1f))
                    TextButton(enabled = !state.busy, onClick = { favorites = favorites - machine.str("id"); if (favorites.isEmpty()) enabled = false; saved = false }) { Text("移除") }
                } }
                if (favorites.isEmpty()) Text("先在洗衣房收藏常用机器")
                Row { Text("空闲提醒", Modifier.weight(1f)); Switch(enabled, { enabled = it; saved = false }, enabled = loaded && !state.busy) }
                windows.forEachIndexed { index, window -> OutlinedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text("时段 ${index + 1}", Modifier.weight(1f)); TextButton(onClick = { windows = windows.filterIndexed { i, _ -> i != index }; saved = false }, enabled = !state.busy) { Text("删除") } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(window.start, { v -> windows = windows.mapIndexed { i, w -> if (i == index) w.copy(start = v.take(5)) else w }; saved = false }, label = { Text("开始 HH:mm") }, modifier = Modifier.weight(1f), enabled = !state.busy, singleLine = true)
                        OutlinedTextField(window.end, { v -> windows = windows.mapIndexed { i, w -> if (i == index) w.copy(end = v.take(5)) else w }; saved = false }, label = { Text("结束 HH:mm") }, modifier = Modifier.weight(1f), enabled = !state.busy, singleLine = true)
                    }
                    Text("星期（按开始时间所在日，支持跨午夜）", style = MaterialTheme.typography.bodySmall)
                    (1..7).toList().chunked(4).forEach { days -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { days.forEach { day ->
                        FilterChip(day in window.days, { windows = windows.mapIndexed { i, w -> if (i == index) w.copy(days = if (day in w.days) w.days - day else w.days + day) else w }; saved = false }, label = { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]) }, enabled = !state.busy)
                    } } }
                } } }
                if (windows.size < 3) TextButton(onClick = { windows = windows + ReminderWindow(); saved = false }, enabled = loaded && !state.busy) { Text("添加时间段") }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                if (saved) Text("已保存", color = MaterialTheme.colorScheme.primary)
            }
        }, dismissButton = { TextButton(onClick = close, enabled = !state.busy) { Text("关闭") } }, confirmButton = { TextButton(enabled = loaded && !state.busy, onClick = {
            val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
            if (windows.any { !time.matches(it.start) || !time.matches(it.end) || it.start == it.end || it.days.isEmpty() } || (enabled && (favorites.isEmpty() || windows.isEmpty()))) {
                error = clientFailure(IllegalArgumentException(), "请先收藏机器，并为每个时段选择星期和不同的有效起止时间")
            } else {
                val body = JSONObject().put("enabled", enabled).put("favorites", JSONArray(favorites.toList())).put("timeWindows", JSONArray(windows.map { JSONObject().put("start", it.start).put("end", it.end).put("days", JSONArray(it.days.sorted())) }))
                error = null
                vm.mutate(null) {
                    try {
                        if (!current()) throw CancellationException("Account changed")
                        vm.api.call("/api/user/laundry-preferences", "PATCH", body)
                        if (current()) saved = true
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (problem: Exception) { if (current()) error = clientFailure(problem, "偏好保存失败，编辑内容已保留"); throw problem }
                }
            }
        }) { Text(if (state.busy) "正在保存…" else "保存") } })
    }
}
