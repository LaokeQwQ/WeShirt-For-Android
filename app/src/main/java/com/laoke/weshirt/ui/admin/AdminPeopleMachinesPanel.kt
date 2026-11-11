package com.laoke.weshirt.ui.admin

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.laoke.weshirt.data.AppState
import org.json.JSONObject

@Composable internal fun AdminUsersPanel(c: AdminController, state: AppState, operate: Boolean) {
    var query by remember { mutableStateOf("") }; var dorm by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf<JSONObject?>(null) }
    val choices = allowedBuildings(state, "adminUsers")
    val targets = allowedBuildings(state, "adminUsers", true)
    val global = globalOperation(state, "adminUsers")
    val path = "/api/admin/users?q=${Uri.encode(query.trim())}&dorm=${Uri.encode(dorm)}"
    LaunchedEffect(dorm) { c.load(path) }
    DisposableEffect(Unit) { onDispose { phone = null } }
    AdminChoice("住宿楼筛选", dorm, listOf("" to "全部授权住宿楼") + choices + if (global) listOf("unbound" to "未绑定住宿楼") else emptyList(), { dorm = it }, !c.saving)
    AdminField("搜索昵称 / 账号 ID / 钉钉 ID", query, { query = it.take(100) }, !c.saving)
    AdminAction("搜索用户", !c.saving) { c.load(path) }
    AdminAction("补全已绑定用户资料", global && !c.saving, "会向钉钉查询已绑定用户的缺失资料，并记录操作日志。") { c.act("/api/admin/backfill-ding-profile?apply=1", refresh = path) }
    Text("手机号默认脱敏；查看全号会单独留档。")
    c.document.arrayRows("items").forEach { user -> key(user.text("id")) {
        var draft by remember(user.text("dormBuilding")) { mutableStateOf(user.text("dormBuilding")) }
        var nick by remember(user.text("nick")) { mutableStateOf(user.text("nick")) }
        var notify by remember(user.optBoolean("notifyOn",true)) { mutableStateOf(user.optBoolean("notifyOn",true)) }
        val mayEdit = operate && (global || targets.any { it.first == user.text("dormBuilding") })
        AdminCard(user.text("nick").ifBlank { user.text("id") }) {
            AdminFacts(user, listOf("id" to "账号", "dormBuilding" to "楼栋", "dingBound" to "钉钉绑定", "dingNick" to "钉钉昵称", "mobile" to "手机号"))
            AdminField("昵称",nick,{nick=it.take(16)},mayEdit && !c.saving)
            AdminToggle("接收钉钉提醒",notify,{notify=it},mayEdit && !c.saving)
            AdminAction("保存昵称与通知偏好",mayEdit && !c.saving,"确认修改此用户的资料与提醒开关？") {
                if(nick.isBlank()) c.localError("昵称不能为空") else c.act("/api/admin/user/${Uri.encode(user.text("id"))}",body=JSONObject().put("nick",nick.trim()).put("notifyOn",notify),refresh=path)
            }
            AdminChoice("住宿楼", draft, (if (global) listOf("" to "未绑定") else emptyList()) + targets, { draft = it }, mayEdit && !c.saving)
            AdminAction("保存住宿楼", mayEdit && !c.saving && draft != user.text("dormBuilding"), "更换用户住宿楼会改变其频道和楼栋访问范围。") { c.act("/api/admin/user/${Uri.encode(user.text("id"))}", body = JSONObject().put("dormBuilding", draft.ifBlank { null } ?: JSONObject.NULL), refresh = path) }
            AdminAction("查看手机号全号", !c.saving && !c.loading, "此次查看会记入审计日志，仅在当前弹窗中显示。") { c.inspect("/api/admin/user/${Uri.encode(user.text("id"))}?phone=1") { phone = it.optJSONObject("user") } }
            if (user.optBoolean("dingBound")) AdminAction("解除钉钉绑定", mayEdit && !c.saving, "解绑后该用户将收不到钉钉取衣提醒。") { c.act("/api/admin/user/${Uri.encode(user.text("id"))}", body = JSONObject().put("unbindDing", true), refresh = path) }
            AdminAction("删除用户", mayEdit && !c.saving, "将删除该用户及其留言，不可恢复。") { c.act("/api/admin/user/${Uri.encode(user.text("id"))}", "DELETE", refresh = path) }
        }
    } }
    phone?.let { value -> AlertDialog(onDismissRequest = { phone = null }, title = { Text("手机号") }, text = { Text(value.text("mobile").ifBlank { "未记录手机号" }) }, confirmButton = { TextButton({ phone = null }) { Text("隐藏并关闭") } }) }
}

@Composable internal fun AdminMachinesPanel(c: AdminController, state: AppState, operate: Boolean) {
    var mode by remember { mutableStateOf("machines") }; var dorm by remember { mutableStateOf("") }
    val path = "/api/admin/machines" + if (dorm.isBlank() || mode == "mapping") "" else "?dorm=${Uri.encode(dorm)}"
    val global = globalOperation(state, "adminMachines")
    LaunchedEffect(path, mode) { c.load(path) }
    AdminChoice("管理项目", mode, listOf("machines" to "机器状态与登记", "mapping" to "3D 机位绑定"), { mode = it }, !c.saving)
    if (mode == "machines") AdminChoice("住宿楼", dorm, listOf("" to "全部授权住宿楼") + allowedBuildings(state, "adminMachines") + if (global) listOf("unbound" to "归属未知") else emptyList(), { dorm = it }, !c.saving)
    AdminAction("刷新机器信息", !c.saving, if(mode == "mapping") "刷新会重置尚未保存的机位选择。" else null) { c.load(path) }
    if (mode == "mapping") {
        AdminMappingEditor(c, state, global && operate)
        return
    }
    AdminFacts(c.document, listOf("updatedAt" to "上游数据时间", "total" to "机器总数"))
    val targets = allowedBuildings(state, "adminMachines", true).map { it.first }
    c.document.arrayRows("rows").forEach { machine -> key(machine.text("id")) {
        val mayEdit = operate && (global || machine.text("dormBuilding") in targets)
        AdminCard(machine.text("name").ifBlank { machine.text("id") }) {
            AdminFacts(machine, listOf("id" to "设备", "dormBuildingName" to "楼栋", "floor" to "楼层", "kind" to "类型", "state" to "状态", "finishAt" to "预计完成", "queueCount" to "排队人数"))
            val occupant = machine.optJSONObject("occupiedBy")
            if (occupant != null) {
                Text("登记人：${occupant.text("nick")}")
                AdminAction("清除本地登记", mayEdit && !c.saving, "只清除本地登记，不会停止洗衣机。请先核实现场情况。") { c.act("/api/admin/machine/${Uri.encode(machine.text("id"))}/clear-occupancy", refresh = path) }
            }
            if (machine.optInt("queueCount") > 0) AdminAction("清除此机排队", mayEdit && !c.saving, "将清除当前机器所有排队记录。") { c.act("/api/admin/machine/${Uri.encode(machine.text("id"))}/clear-queue", refresh = path) }
            Text("预计完成时间不能单独证明洗衣已结束，状态以上游最近更新为准。")
        }
    } }
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
