package com.laoke.weshirt.ui.admin

import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.epoch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class AdminUserForm(val dorm: String, val nick: String, val notify: Boolean)
private data class AdminUserDraft(val original: AdminUserForm, val value: AdminUserForm)

private fun userForm(user: JSONObject) = AdminUserForm(user.text("dormBuilding"), user.text("nick"), user.optBoolean("notifyOn", true))

@Composable internal fun AdminUsersPanel(c: AdminController, state: AppState, operate: Boolean, initialUserQuery: String = "") {
    var query by remember(initialUserQuery) { mutableStateOf(initialUserQuery.take(100)) }; var dorm by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var batchDorm by remember { mutableStateOf("") }
    var batchResult by remember { mutableStateOf<JSONObject?>(null) }
    var exportPath by remember { mutableStateOf("") }
    var expandedUsers by remember { mutableStateOf(setOf<String>()) }
    val drafts = remember { mutableStateMapOf<String, AdminUserDraft>() }
    val resolver = LocalContext.current.contentResolver
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && exportPath.isNotBlank()) c.exportCsv(exportPath) { csv ->
            withContext(Dispatchers.IO) { resolver.openOutputStream(uri, "w")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("文件无法写入") }
        }
        exportPath = ""
    }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val choices = allowedBuildings(state, "adminUsers")
    val targets = allowedBuildings(state, "adminUsers", true)
    val global = globalOperation(state, "adminUsers")
    val path = "/api/admin/users?q=${Uri.encode(query.trim())}&dorm=${Uri.encode(dorm)}"
    LaunchedEffect(dorm, initialUserQuery) { selected = emptySet(); batchResult = null; c.load(path) }
    DisposableEffect(Unit) { onDispose { phone = null; exportPath = ""; selected = emptySet(); batchResult = null } }
    val document = c.document
    val users = remember(document) { document.arrayRows("items") }
    val bulk = document.optJSONObject("bulk")
    val selectable = remember(users) { users.filter { it.optBoolean("canOperate") }.map { it.text("id") }.toSet() }
    LaunchedEffect(document, c.loading) {
        if (!c.loading) {
            selected = selected.intersect(selectable)
            val sources = users.associateBy { it.text("id") }
            expandedUsers = expandedUsers.intersect(sources.keys)
            drafts.keys.filter { drafts[it]?.original != sources[it]?.let(::userForm) }.forEach { drafts.remove(it) }
        }
    }
    AdminPanelList(Modifier.testTag("admin-users-list")) {
        adminReadOnlyNotice(operate)
        item(key = "user-tools") { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AdminChoice("住宿楼筛选", dorm, listOf("" to "全部授权住宿楼") + choices + if (global) listOf("unbound" to "未绑定住宿楼") else emptyList(), { dorm = it }, !c.saving)
            AdminField("搜索昵称 / 账号 ID / 钉钉 ID", query, { query = it.take(100) }, !c.saving)
            AdminAction("搜索用户", !c.saving) { c.load(path, keepVisible = true) }
            AdminAction("导出当前筛选用户（脱敏 CSV）", !c.saving && !c.loading, "导出只包含当前查看范围，手机号保持脱敏；请选择文件保存位置。") {
                exportPath = "/api/admin/users.csv?q=${Uri.encode(query.trim())}&dorm=${Uri.encode(dorm)}"
                exportFile.launch("weshirt-users-${java.time.LocalDate.now()}.csv")
            }
            AdminAction("补全已绑定用户资料", global && !c.saving, "会向钉钉查询已绑定用户的缺失资料，并记录操作日志。") { c.act("/api/admin/backfill-ding-profile?apply=1", refresh = path) }
            Text("手机号默认脱敏；查看全号会单独留档。")
            if (!c.loading || users.isNotEmpty()) Text("共 ${users.size} 人 · 点开用户查看资料和管理操作", style = MaterialTheme.typography.bodySmall)
            fun submitBatch(action: String, notify: Boolean? = null) {
                val ids = selected.intersect(selectable).take(50)
                if (ids.isEmpty()) return
                val body = JSONObject().put("ids", JSONArray(ids)).put("action", action).put("confirmed", true)
                if (action == "setNotify") body.put("notifyOn", notify)
                if (action == "setDorm") body.put("dormBuilding", if (batchDorm == "__unbound__") JSONObject.NULL else batchDorm)
                c.act("/api/admin/users/batch", body = body, refresh = path, success = { result ->
                    batchResult = result
                    selected = result.arrayRows("results").filter { !it.optBoolean("ok") }.map { it.text("id") }.toSet()
                })
            }
            if (operate && bulk?.optBoolean("operate") == true) AdminCard("批量管理") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.isNotEmpty() && selected.containsAll(selectable), { checked -> selected = if (checked) selectable.take(50).toSet() else emptySet() }, enabled = !c.saving && selectable.isNotEmpty())
                    Text("选择当前可操作用户 · 已选 ${selected.size} / 50")
                }
                Text("最多选择 50 人；每个用户单独校验权限。有未取衣任务或现场登记时不能批量删除。", style = MaterialTheme.typography.bodySmall)
                AdminChoice("目标住宿楼", batchDorm, listOf("" to "请选择") + targets + if (global) listOf("__unbound__" to "清除住宿楼绑定") else emptyList(), { batchDorm = it }, !c.saving)
                AdminAction("批量修改住宿楼", selected.isNotEmpty() && batchDorm.isNotBlank() && !c.saving, "确认修改所选 ${selected.size} 人的住宿楼？这会改变其频道和楼栋访问范围。") { submitBatch("setDorm") }
                AdminAction("批量开启提醒", selected.isNotEmpty() && !c.saving, "确认开启所选 ${selected.size} 人的钉钉提醒？") { submitBatch("setNotify", true) }
                AdminAction("批量关闭提醒", selected.isNotEmpty() && !c.saving, "确认关闭所选 ${selected.size} 人的钉钉提醒？") { submitBatch("setNotify", false) }
                AdminAction("批量删除用户", selected.isNotEmpty() && !c.saving, "确认永久删除所选 ${selected.size} 人及其留言？有未取衣任务、现场登记或高权限保护的账号将保留。") { submitBatch("delete") }
            }
            batchResult?.let { result -> AdminCard("批量处理结果") {
                Text("成功 ${result.optInt("succeeded")} 人，未完成 ${result.optInt("failed")} 人")
                result.arrayRows("results").filter { !it.optBoolean("ok") }.forEach { item ->
                    val error = item.optJSONObject("error")
                    Text("${item.text("id")}：${error?.text("message").orEmpty()}\n状态码 ${item.optInt("status")} · 事件 ${error?.text("eventId").orEmpty().ifBlank { result.text("eventId") }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            } }
        } }
        items(users, key = { "user:" + it.text("id") }) { user ->
            val id = user.text("id")
            val dormBuilding = user.text("dormBuilding")
            val original = remember(user) { userForm(user) }
            val edit = drafts[id]?.takeIf { it.original == original }?.value ?: original
            val mayEdit = operate && user.optBoolean("canOperate") && (global || targets.any { it.first == dormBuilding })
            val expanded = id in expandedUsers
            val buildingName = state.buildings.find { it.code == dormBuilding }?.name ?: dormBuilding.ifBlank { "未绑定住宿楼" }
            AdminUserCard(user.text("nick").ifBlank { user.text("id") }, "$buildingName · ${if (user.optBoolean("dingBound")) "已绑定钉钉" else "未绑定钉钉"}", expanded, {
                expandedUsers = if (expanded) expandedUsers - id else expandedUsers + id
            }, selected = if (mayEdit) id in selected else null, select = { checked ->
                if (!checked) selected = selected - id else if (selected.size < 50 && id in selectable) selected = selected + id else c.localError("每次最多选择 50 人")
            }, selectionEnabled = !c.saving) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) { Text("账号 ID：${user.text("id")}", style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = { clipboard?.setPrimaryClip(ClipData.newPlainText("WeShirt 用户 ID", user.text("id"))) }) { Icon(Icons.Outlined.ContentCopy, "复制用户 ID") }
                }
                AdminFacts(user, listOf("dingNick" to "钉钉昵称", "mobile" to "手机号"))
                AdminField("昵称", edit.nick, { drafts[id] = AdminUserDraft(original, edit.copy(nick = it.take(16))) }, mayEdit && !c.saving)
                AdminToggle("接收钉钉提醒", edit.notify, { drafts[id] = AdminUserDraft(original, edit.copy(notify = it)) }, mayEdit && !c.saving)
                AdminAction("保存昵称与通知偏好",mayEdit && !c.saving,"确认修改此用户的资料与提醒开关？") {
                    if (edit.nick.isBlank()) c.localError("昵称不能为空") else c.act("/api/admin/user/${Uri.encode(id)}", body = JSONObject().put("nick", edit.nick.trim()).put("notifyOn", edit.notify), refresh = path)
                }
                AdminChoice("住宿楼", edit.dorm, (if (global) listOf("" to "未绑定") else emptyList()) + targets, { drafts[id] = AdminUserDraft(original, edit.copy(dorm = it)) }, mayEdit && !c.saving)
                AdminAction("保存住宿楼", mayEdit && !c.saving && edit.dorm != dormBuilding, "更换用户住宿楼会改变其频道和楼栋访问范围。") { c.act("/api/admin/user/${Uri.encode(user.text("id"))}", body = JSONObject().put("dormBuilding", edit.dorm.ifBlank { null } ?: JSONObject.NULL), refresh = path) }
                AdminAction("查看手机号全号", !c.saving && !c.loading, "此次查看会记入审计日志，仅在当前弹窗中显示。") { c.inspect("/api/admin/user/${Uri.encode(user.text("id"))}?phone=1") { phone = it.optJSONObject("user") } }
                if (user.optBoolean("dingBound")) AdminAction("解除钉钉绑定", mayEdit && !c.saving, "解绑后该用户将收不到钉钉取衣提醒。") { c.act("/api/admin/user/${Uri.encode(user.text("id"))}", body = JSONObject().put("unbindDing", true), refresh = path) }
                AdminAction("删除用户", mayEdit && !c.saving, "将删除该用户及其留言，不可恢复。") { c.act("/api/admin/user/${Uri.encode(user.text("id"))}", "DELETE", refresh = path) }
            }
        }
    }
    phone?.let { value -> AlertDialog(onDismissRequest = { phone = null }, title = { Text("手机号") }, text = { Text(value.text("mobile").ifBlank { "未记录手机号" }) }, confirmButton = { TextButton({ phone = null }) { Text("隐藏并关闭") } }) }
}

@Composable internal fun AdminUserCard(name: String, summary: String, expanded: Boolean, toggle: () -> Unit, selected: Boolean? = null, select: (Boolean) -> Unit = {}, selectionEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = toggle).testTag("admin-user-expand"), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) Checkbox(selected, select, enabled = selectionEnabled)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起用户资料" else "展开用户资料与操作")
            }
            if (expanded) { HorizontalDivider(); content() }
        }
    }
}

@Composable internal fun AdminMachinesPanel(c: AdminController, state: AppState, operate: Boolean, onUserSelected: (String) -> Unit = {}) {
    var mode by remember { mutableStateOf("machines") }; var dorm by remember { mutableStateOf("") }
    val path = "/api/admin/machines" + if (dorm.isBlank() || mode == "mapping") "" else "?dorm=${Uri.encode(dorm)}"
    val global = globalOperation(state, "adminMachines")
    val document = c.document
    val rows = remember(document) { document.arrayRows("rows") }
    LaunchedEffect(path, mode) { if (mode != "feedback") c.load(path) }
    LaunchedEffect(state.updatedAt, path, mode) {
        if (mode == "machines" && !c.saving && !c.loading && c.loadedPath == path &&
            state.updatedAt != null && state.updatedAt != epoch(document.text("updatedAt"))) {
            c.load(path, keepVisible = true)
        }
    }
    AdminPanelList {
        adminReadOnlyNotice(operate)
        item(key = "machine-tools") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AdminChoice("管理项目", mode, listOf("machines" to "机器状态与登记", "mapping" to "3D 机位绑定", "feedback" to "机器信息反馈"), { mode = it }, !c.saving)
                if (mode == "machines") AdminChoice("住宿楼", dorm, listOf("" to "全部授权住宿楼") + allowedBuildings(state, "adminMachines") + if (global) listOf("unbound" to "归属未知") else emptyList(), { dorm = it }, !c.saving)
                if (mode != "feedback") AdminAction("刷新机器信息", !c.saving, if (mode == "mapping") "刷新会重置尚未保存的机位选择。" else null) {
                    c.load(path, keepVisible = mode == "machines")
                }
                if (mode == "machines") {
                    Column { AdminFacts(document, listOf("updatedAt" to "上游数据时间", "total" to "机器总数")) }
                }
            }
        }
        when (mode) {
            "mapping" -> item(key = "machine-mapping") { AdminMappingEditor(c, state, global && operate) }
            "feedback" -> item(key = "machine-feedback") { AdminMachineFeedbackPanel(c, state, operate) }
            else -> {
                val targets = allowedBuildings(state, "adminMachines", true).map { it.first }
                items(rows, key = { "machine:" + it.text("id") }) { machine ->
                    val mayEdit = operate && (global || machine.text("dormBuilding") in targets)
                    val name = machine.text("displayName").ifBlank { machine.text("remarkName").ifBlank { machine.text("name").ifBlank { machine.text("id") } } }
                    AdminCard(name) {
                        machine.text("originalName").takeIf { it.isNotBlank() && it != name }?.let { Text("原名：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        AdminFacts(machine, listOf("id" to "设备", "dormBuildingName" to "楼栋", "floor" to "楼层", "kind" to "类型", "state" to "状态", "finishAt" to "预计完成", "queueCount" to "排队人数"))
                        val occupant = machine.optJSONObject("occupiedBy")
                        if (occupant != null) {
                            if (occupant.text("userId").isNotBlank() && state.access.optJSONObject("permissions")?.optJSONObject("adminUsers")?.optBoolean("view") == true) TextButton(onClick = { onUserSelected(occupant.text("userId")) }) { Text("登记人：${occupant.text("nick")} · 查看用户") }
                            else Text("登记人：${occupant.text("nick")}")
                            AdminAction("清除本地登记", mayEdit && !c.saving, "只清除本地登记，不会停止洗衣机。请先核实现场情况。") { c.act("/api/admin/machine/${Uri.encode(machine.text("id"))}/clear-occupancy", refresh = path) }
                        }
                        if (machine.optInt("queueCount") > 0) AdminAction("清除此机排队", mayEdit && !c.saving, "将清除当前机器所有排队记录。") { c.act("/api/admin/machine/${Uri.encode(machine.text("id"))}/clear-queue", refresh = path) }
                        Text("预计完成时间不能单独证明洗衣已结束，状态以上游最近更新为准。")
                    }
                }
            }
        }
    }
}

private val adminSlots = listOf("D1", "1", "2", "3", "4", "D2", "7", "6", "5")
@Composable private fun AdminMappingEditor(c: AdminController, state: AppState, operate: Boolean) {
    val mapping = c.document.optJSONObject("mapping")?.optJSONObject("mapping")
    var draft by remember(mapping?.toString()) { mutableStateOf(mapping?.copyObject() ?: JSONObject()) }
    val rows = c.document.arrayRows("rows")
    AdminCard("场景机位") {
        Text("仅全局机器管理权限可更改机位。空值表示解除绑定；同一设备只绑定一个机位。")
        adminSlots.forEach { slot ->
            AdminChoice("机位 $slot", draft.text(slot), listOf("" to "未绑定") + rows.map { it.text("id") to "${it.text("name")} · ${it.text("id")}" }, { value -> draft = draft.copyObject().put(slot, value.ifBlank { null } ?: JSONObject.NULL) }, operate && !c.saving)
        }
        AdminAction("保存机位绑定", operate && !c.saving && mapping != null, "会更新 Web 和安卓原生 3D 场景中的设备对应关系。") {
            val ids = adminSlots.map(draft::text).filter(String::isNotBlank)
            if (ids.distinct().size != ids.size || ids.any { id -> rows.none { it.text("id") == id } }) c.localError("设备重复或不在最新列表中，请重新选择")
            else c.act("/api/mapping?building=b1", body = JSONObject().put("mapping", draft), refresh = "/api/admin/machines")
        }
    }
}
