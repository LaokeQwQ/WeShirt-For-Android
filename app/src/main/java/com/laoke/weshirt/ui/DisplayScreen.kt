package com.laoke.weshirt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.data.MachineBoardState
import com.laoke.weshirt.data.machineBoardStatus
import com.laoke.weshirt.data.sortMachinesByAvailability
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Public presentation of the current building's already-filtered state; never fetches data. */
@Composable fun DisplayScreen(state: AppState, close: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val now by produceState(System.currentTimeMillis(), owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = System.currentTimeMillis(); delay(1000 - value % 1000) }
        }
    }
    DisposableEffect(view, owner) {
        val original = view.keepScreenOn
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.keepScreenOn = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> view.keepScreenOn = original
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.keepScreenOn = true
        onDispose { owner.lifecycle.removeObserver(observer); view.keepScreenOn = original }
    }
    BackHandler(onBack = close)
    val clock = remember(now / 1000) { displayClock.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())) }
    val building = state.buildings.find { it.code == state.building }?.name ?: "当前住宿楼"
    val floors = remember(state.machines) {
        state.machines.groupBy { it.floorCode.ifBlank { it.floorName }.ifBlank { "unassigned" } }
            .entries.sortedWith(compareBy<Map.Entry<String, List<Machine>>> { displayFloorOrder(it.key) }.thenBy { it.key })
    }
    val idle = state.machines.count { machineBoardStatus(it, now).state == MachineBoardState.AVAILABLE }
    val occupied = state.machines.count { machineBoardStatus(it, now).state in setOf(MachineBoardState.RUNNING, MachineBoardState.REVIEW, MachineBoardState.PICKUP) }
    val faulty = state.machines.count { machineBoardStatus(it, now).state == MachineBoardState.FAULT }
    val stale = state.updatedAt?.let { ((now - it).coerceAtLeast(0) / 60_000).toInt() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 210.dp else 148.dp),
                modifier = Modifier.fillMaxSize().testTag("display-board"),
                contentPadding = PaddingValues(if (wide) 28.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("display-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(clock, fontSize = if (wide) 48.sp else 34.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = close) { Text("返回列表") }
                        }
                        Text(building, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                item("display-idle", span = { GridItemSpan(maxLineSpan) }, contentType = "summary") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state.updatedAt == null && state.machines.isEmpty()) "—" else idle.toString(), fontSize = if (wide) 136.sp else 100.sp, fontWeight = FontWeight.Black, color = Color(0xFF22A55B))
                        Text("台空闲", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item("display-summary", span = { GridItemSpan(maxLineSpan) }, contentType = "summary") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("总台数" to state.machines.size, "空闲" to idle, "使用中" to occupied, "故障" to faulty).forEach { (label, count) ->
                            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(count.toString(), fontSize = if (wide) 30.sp else 25.sp, fontWeight = FontWeight.Bold)
                                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (stale != null && stale >= 3) item("display-stale", span = { GridItemSpan(maxLineSpan) }, contentType = "notice") {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Text("数据已 $stale 分钟未更新，请以现场为准", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (state.machines.isEmpty()) item("display-empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    Text(if (state.loading) "正在同步机器数据…" else "这栋楼暂无机器数据", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                floors.forEach { (code, machines) ->
                    val floorName = machines.firstOrNull { it.floorName.isNotBlank() }?.floorName ?: when {
                        code == "unassigned" -> "未分层"
                        code.contains("楼") || code.contains("层") -> code
                        else -> "$code 层"
                    }
                    item("floor:$code", span = { GridItemSpan(maxLineSpan) }, contentType = "floor") {
                        Text(floorName, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    items(sortMachinesByAvailability(machines, now), key = { "display-machine:${it.id}" }, contentType = { "machine" }) { machine -> DisplayMachine(machine, now, wide) }
                }
                state.updatedAt?.let { at -> item("display-updated", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                    Text("最近更新 ${displayClock.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))} · 预计时间归零仍需设备确认", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
}

@Composable private fun DisplayMachine(machine: Machine, now: Long, wide: Boolean) {
    val status = machineBoardStatus(machine, now)
    val colors = when (status.state) {
        MachineBoardState.AVAILABLE -> Color(0xFF86EFAC) to Color(0xFF052E16)
        MachineBoardState.RUNNING -> Color(0xFFFDE68A) to Color(0xFF451A03)
        MachineBoardState.PICKUP, MachineBoardState.REVIEW -> Color(0xFFFDBA74) to Color(0xFF431407)
        MachineBoardState.FAULT -> Color(0xFFFCA5A5) to Color(0xFF450A0A)
        MachineBoardState.UNKNOWN -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(Modifier.fillMaxWidth().heightIn(min = if (wide) 168.dp else 148.dp).semantics(mergeDescendants = true) {}, shape = RoundedCornerShape(18.dp), color = colors.first, contentColor = colors.second) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(machine.name.ifBlank { if (machine.slot.isBlank()) "未命名机器" else "${machine.slot} 号机" }, fontSize = if (wide) 26.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when (machine.kind) { "dryer" -> "烘干机"; "washer" -> "洗衣机"; else -> "设备" }, style = MaterialTheme.typography.labelLarge)
            Text(status.label, fontSize = if (wide) 24.sp else 20.sp, fontWeight = FontWeight.Bold)
            status.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

private val displayClock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)
private fun displayFloorOrder(code: String): Int {
    val number = Regex("-?\\d+").find(code)?.value?.toIntOrNull() ?: return Int.MAX_VALUE
    return if (code.startsWith("B", ignoreCase = true) && number > 0) -number else number
}
