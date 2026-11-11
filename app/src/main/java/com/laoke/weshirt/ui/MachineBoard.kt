@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.laoke.weshirt.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.data.MachineBoardState
import com.laoke.weshirt.data.MachineBoardStatus
import com.laoke.weshirt.data.machineBoardStatus
import com.laoke.weshirt.data.sortMachinesByAvailability

/** Receives only the selected building's machines. It does not fetch or retain account information. */
@Composable
fun MachineBoard(
    machines: List<Machine>,
    favorites: Set<String>,
    now: Long,
    onMachine: (Machine) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var cards by rememberSaveable { mutableStateOf(false) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    val ordered = remember(machines, now) { sortMachinesByAvailability(machines, now) }
    val visible = remember(ordered, filter, onlyFavorites, favorites) {
        ordered.filter { (filter == "all" || it.kind == filter) && (!onlyFavorites || it.id in favorites) }
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
        item(key = "board-controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("最快可用", style = MaterialTheme.typography.titleMedium)
                        Text("按设备状态与预计结束时间排序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(checked = !cards, onCheckedChange = { cards = false }, modifier = Modifier.size(48.dp).testTag("machine-layout-list")) {
                        Icon(Icons.Outlined.ViewList, "列表视图", tint = if (!cards) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(checked = cards, onCheckedChange = { cards = true }, modifier = Modifier.size(48.dp).testTag("machine-layout-cards")) {
                        Icon(Icons.Outlined.GridView, "卡片视图", tint = if (cards) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "全部", "washer" to "洗衣", "dryer" to "烘干").forEach { (kind, label) ->
                        FilterChip(
                            selected = filter == kind,
                            onClick = { filter = kind },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("machine-filter-$kind")
                        )
                    }
                    FilterChip(selected = onlyFavorites, onClick = { onlyFavorites = !onlyFavorites }, label = { Text("常用") }, modifier = Modifier.heightIn(min = 48.dp).testTag("machine-filter-favorites"))
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
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
private fun BoardMachineItem(machine: Machine, favorite: Boolean, status: MachineBoardStatus, cards: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("machine:${machine.id}"), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cards) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MachineIcon(machine)
                    Spacer(Modifier.weight(1f))
                    if (favorite) Icon(Icons.Outlined.Star, "已收藏", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(machine.name.ifBlank { "未命名设备" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                MachineStatusBadge(status)
                status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MachineIcon(machine)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(machine.name.ifBlank { "未命名设备" }, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (favorite) Icon(Icons.Outlined.Star, "已收藏", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        MachineStatusBadge(status)
                        status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val care = listOfNotNull("已用消毒液".takeIf { machine.disinfectant }, "已自清洁".takeIf { machine.selfCleaned }).joinToString(" · ")
            if (care.isNotEmpty()) Text(care, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (machine.note.isNotBlank()) Text("备注：${machine.note}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (machine.health.isNotBlank()) Text(machine.health, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = if (status.state == MachineBoardState.FAULT || status.state == MachineBoardState.REVIEW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MachineIcon(machine: Machine) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Icon(if (machine.kind == "dryer") Icons.Outlined.Air else Icons.Outlined.LocalLaundryService, null, Modifier.padding(9.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
