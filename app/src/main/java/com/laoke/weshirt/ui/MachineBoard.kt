@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.laoke.weshirt.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.data.MachineBoardState
import com.laoke.weshirt.data.MachineBoardStatus
import com.laoke.weshirt.data.machineBoardStatus
import com.laoke.weshirt.data.sortMachinesByAvailability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class MachineBoardSummary(val total: Int, val available: Int, val occupied: Int, val faulty: Int)

internal fun machineBoardSummary(machines: List<Machine>) = MachineBoardSummary(
    machines.size,
    machines.count { it.state == "idle" && !it.suspectedUncollected },
    machines.count { it.state in setOf("occupied", "abnormal") || it.state == "idle" && it.suspectedUncollected },
    machines.count { it.state == "faulty" }
)

internal fun machineBoardStaleMinutes(updatedAt: Long?, now: Long): Long? = updatedAt?.let { ((now - it).coerceAtLeast(0) / 60_000) }

/** Receives only the selected building's machines. It does not fetch or retain account information. */
@Composable
fun MachineBoard(
    machines: List<Machine>,
    favorites: Set<String>,
    now: Long,
    onMachine: (Machine) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    updatedAt: Long? = null
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var cards by rememberSaveable { mutableStateOf(false) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var floor by rememberSaveable { mutableStateOf<String?>(null) }
    val floors = remember(machines) { machines.distinctBy { it.floorCode }.map { it.floorCode to it.floorName.ifBlank { it.floorCode.ifBlank { "其他位置" } } } }
    val summary = remember(machines) { machineBoardSummary(machines) }
    val staleMinutes = machineBoardStaleMinutes(updatedAt, now)
    LaunchedEffect(floors) { if (floor != null && floors.none { it.first == floor }) floor = null }
    val ordered = remember(machines, now) { sortMachinesByAvailability(machines, now) }
    val visible = remember(ordered, floor, filter, onlyFavorites, favorites) {
        ordered.filter { (floor == null || it.floorCode == floor) && (filter == "all" || it.kind == filter) && (!onlyFavorites || it.id in favorites) }
    }
    val groups = remember(visible) {
        listOf(
            "washer" to visible.filter { it.kind == "washer" },
            "dryer" to visible.filter { it.kind == "dryer" },
            "other" to visible.filter { it.kind != "washer" && it.kind != "dryer" }
        ).filter { it.second.isNotEmpty() }
    }
    LazyVerticalGrid(
        columns = if (cards) GridCells.Adaptive(168.dp) else GridCells.Fixed(1),
        modifier = modifier.fillMaxSize().testTag("machine-board"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (header != null) item(key = "board-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
        item(key = "board-summary", span = { GridItemSpan(maxLineSpan) }, contentType = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.2f) 2 else 4
                    val summaries = listOf("本楼机器" to summary.total, "空闲" to summary.available, "占用" to summary.occupied, "故障" to summary.faulty)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        summaries.chunked(columns).forEach { chunk ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                chunk.forEach { (label, count) ->
                                    Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = when (label) { "空闲" -> MaterialTheme.colorScheme.primary; "故障" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface })
                                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(updatedAt?.let { "更新于 " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "更新时间待确认", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (staleMinutes != null && staleMinutes >= 3) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text("数据已 $staleMinutes 分钟未更新，请以现场为准", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        item(key = "board-controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("最快可用", style = MaterialTheme.typography.titleMedium)
                        Text("空闲优先 · 按预计结束排序", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(checked = !cards, onCheckedChange = { cards = false }, modifier = Modifier.size(48.dp).testTag("machine-layout-list")) {
                        Icon(Icons.Outlined.ViewList, "列表视图", tint = if (!cards) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(checked = cards, onCheckedChange = { cards = true }, modifier = Modifier.size(48.dp).testTag("machine-layout-cards")) {
                        Icon(Icons.Outlined.GridView, "卡片视图", tint = if (cards) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f) 2 else 4
                    val filters = listOf("all" to "全部", "washer" to "洗衣", "dryer" to "烘干", "favorites" to "常用")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filters.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (kind, label) ->
                                    FilterChip(
                                        selected = if (kind == "favorites") onlyFavorites else filter == kind,
                                        onClick = { if (kind == "favorites") onlyFavorites = !onlyFavorites else filter = kind },
                                        label = { Text(label, maxLines = 1) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("machine-filter-$kind"),
                                        leadingIcon = if (kind == "favorites") { { Icon(Icons.Outlined.Star, null, Modifier.size(18.dp)) } } else null
                                    )
                                }
                            }
                        }
                    }
                }
                if (floors.any { it.first.isNotBlank() }) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = floor == null, onClick = { floor = null }, label = { Text("本楼全部") }, modifier = Modifier.heightIn(min = 48.dp).testTag("machine-floor-all"))
                    floors.forEach { (code, name) -> FilterChip(selected = floor == code, onClick = { floor = code }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp).testTag("machine-floor-$code")) }
                }
            }
        }
        if (visible.isEmpty()) item(key = "board-empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (onlyFavorites) "暂无常用机器" else when (filter) { "washer" -> "暂无洗衣机"; "dryer" -> "暂无烘干机"; else -> "暂无机器数据" }, style = MaterialTheme.typography.titleMedium)
                    Text(if (onlyFavorites) "当前分类没有收藏的机器，可先在机器详情中收藏。" else "当前范围没有这类设备，可切换分类或检查楼栋选择。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        groups.forEach { (kind, group) ->
            item(key = "group-$kind", span = { GridItemSpan(maxLineSpan) }, contentType = "section") {
                FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when (kind) { "washer" -> "洗衣机"; "dryer" -> "烘干机"; else -> "其他设备" }, style = MaterialTheme.typography.titleSmall)
                    val available = group.count { it.state == "idle" && !it.suspectedUncollected }
                    Text("${group.size} 台 · $available 台空闲", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(group, key = { "machine:${it.id}" }, contentType = { if (cards) "card" else "row" }) { machine ->
                BoardMachineItem(machine, machine.id in favorites, machineBoardStatus(machine, now), cards) { onMachine(machine) }
            }
        }
    }
}

@Composable
internal fun BoardMachineItem(machine: Machine, favorite: Boolean, status: MachineBoardStatus, cards: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("machine:${machine.id}"), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cards) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MachineNumber(machine)
                    Spacer(Modifier.weight(1f))
                    MachineFavorite(machine.id, favorite)
                }
                MachineName(machine)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MachineNumber(machine)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MachineName(machine)
                    }
                    MachineFavorite(machine.id, favorite)
                }
            }
            FlowRow(Modifier.fillMaxWidth().testTag("machine-status-line:${machine.id}"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                MachineStatusBadge(status)
                status.detail?.let { Text(it, Modifier.testTag("machine-remaining:${machine.id}"), style = if (status.state == MachineBoardState.RUNNING) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
            if (machine.originalName.isNotBlank() && machine.originalName != machine.name) Text("原名：${machine.originalName}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val care = listOfNotNull("已用消毒液".takeIf { machine.disinfectant }, "已自清洁".takeIf { machine.selfCleaned }).joinToString(" · ")
            if (care.isNotEmpty()) Text(care, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (machine.note.isNotBlank()) Text("备注：${machine.note}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (machine.health.isNotBlank()) Text(machine.health, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = if (status.state == MachineBoardState.FAULT || status.state == MachineBoardState.REVIEW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MachineNumber(machine: Machine) {
    Surface(Modifier.width(52.dp).heightIn(min = 58.dp).testTag("machine-slot:${machine.id}"), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(machine.slot.ifBlank { "—" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (machine.kind == "dryer") Icons.Outlined.Air else Icons.Outlined.LocalLaundryService, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MachineName(machine: Machine) {
    Text(machine.name.ifBlank { "未命名设备" }, Modifier.testTag("machine-name:${machine.id}"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    Text(listOf(if (machine.kind == "dryer") "烘干" else if (machine.kind == "washer") "洗衣" else "其他设备", machine.floorName.ifBlank { machine.floorCode }).filter { it.isNotBlank() }.joinToString(" · "),
        Modifier.testTag("machine-type:${machine.id}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MachineFavorite(id: String, favorite: Boolean) {
    Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.ChevronRight, if (favorite) "已收藏" else null,
        Modifier.size(24.dp).testTag("machine-favorite:$id"), tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
}

@Composable
private fun MachineStatusBadge(status: MachineBoardStatus) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (status.state) {
        MachineBoardState.AVAILABLE -> scheme.primaryContainer to scheme.onPrimaryContainer
        MachineBoardState.RUNNING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        MachineBoardState.PICKUP, MachineBoardState.REVIEW -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        MachineBoardState.FAULT -> scheme.errorContainer to scheme.onErrorContainer
        MachineBoardState.UNKNOWN -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = colors.first, contentColor = colors.second) {
        Text(status.label, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}
