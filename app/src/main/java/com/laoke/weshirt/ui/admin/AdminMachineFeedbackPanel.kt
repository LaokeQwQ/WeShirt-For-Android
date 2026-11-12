package com.laoke.weshirt.ui.admin

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.laoke.weshirt.data.AppState
import org.json.JSONObject

/** Own controller: loading feedback must never overwrite the machine list or mapping editor document. */
@Composable internal fun AdminMachineFeedbackPanel(parent: AdminController, state: AppState, operate: Boolean) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(state)
    val binding = "${state.endpoint}|${state.profile?.id}"
    val c = remember(parent, binding) { AdminController(parent.api, scope) { latest.endpoint == state.endpoint && latest.profile?.id == state.profile?.id && latest.access.optJSONObject("permissions")?.optJSONObject("adminMachines")?.optBoolean("view") == true && parent.api.baseUrl == state.endpoint } }
    DisposableEffect(c) { onDispose { c.close() } }
    var dorm by remember(binding) { mutableStateOf("") }
    var status by remember(binding) { mutableStateOf("open") }
    var page by remember(binding) { mutableIntStateOf(1) }
    val statuses = listOf("open" to "待核实", "reviewing" to "核实中", "resolved" to "已处理", "dismissed" to "已关闭")
    val path = "/api/admin/machine-feedback?dorm=${Uri.encode(dorm)}&status=$status&page=$page&limit=20"
    LaunchedEffect(path) { c.load(path) }
    AdminCard("机器信息反馈") {
        AdminChoice("住宿楼", dorm, listOf("" to "全部授权住宿楼") + allowedBuildings(state, "adminMachines"), { dorm = it; page = 1 }, !c.saving)
        AdminChoice("状态", status, listOf("" to "全部状态") + statuses, { status = it; page = 1 }, !c.saving)
        AdminAction("刷新反馈", !c.saving) { c.load(path) }
        c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        c.info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (c.loading) Text("读取中…")
        val pagination = c.document.optJSONObject("pagination")
        Text("第 $page 页 · 共 ${pagination?.optInt("total") ?: 0} 条")
        c.document.arrayRows("feedback").forEach { row -> key(row.text("id"), row.text("updatedAt")) {
            var target by remember { mutableStateOf(row.text("status")) }
            var resolution by remember { mutableStateOf(row.text("resolution")) }
            val name = row.text("displayName").ifBlank { row.text("originalName").ifBlank { row.text("machineId") } }
            AdminCard(name) {
                row.text("originalName").takeIf { it.isNotBlank() && it != name }?.let { Text("原名：$it", style = MaterialTheme.typography.bodySmall) }
                Text("${state.buildings.find { it.code == row.text("dormBuilding") }?.name ?: row.text("dormBuilding")} · ${mapOf("name" to "名称有误", "location" to "机位不符", "state" to "状态不符", "other" to "其他情况")[row.text("type")].orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Text(row.text("detail"))
                if (operate && row.optBoolean("canHandle")) {
                    AdminChoice("处理状态", target, statuses, { target = it }, !c.saving)
                    AdminField("处理说明（关闭反馈时必填）", resolution, { resolution = it.take(300) }, !c.saving)
                    AdminAction("保存处理结果", !c.saving, "$name · ${statuses.find { it.first == target }?.second.orEmpty()}\n$resolution") {
                        if (target in listOf("resolved", "dismissed") && resolution.isBlank()) c.localError("请填写核实和处理说明")
                        else c.act("/api/admin/machine-feedback/${Uri.encode(row.text("id"))}", "PATCH", JSONObject().put("status", target).put("resolution", resolution.trim()), refresh = path)
                    }
                } else if (row.text("resolution").isNotBlank()) Text("处理说明：${row.text("resolution")}")
            }
        } }
        AdminAction("上一页", page > 1 && !c.loading && !c.saving) { page-- }
        AdminAction("下一页", pagination?.optBoolean("hasMore") == true && !c.loading && !c.saving) { page++ }
    }
}
