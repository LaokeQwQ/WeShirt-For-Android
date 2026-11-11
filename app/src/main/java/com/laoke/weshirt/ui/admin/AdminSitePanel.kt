package com.laoke.weshirt.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.data.AppState
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun AdminSitePanel(c: AdminController, state: AppState, operate: Boolean) {
    var page by remember { mutableStateOf("site") }
    val path = if (page == "groups") "/api/admin/building-groups" else "/api/admin/site"
    LaunchedEffect(path) { c.load(path) }
    AdminChoice("管理项目", page, listOf("site" to "网站与楼栋", "members" to "后台成员", "credentials" to "钉钉配置", "groups" to "楼栋通知群"), { page = it }, !c.saving)
    AdminAction("重新读取", !c.saving, "将重新读取服务器配置，尚未保存的设置会被重置。") { c.load(path) }
    val d = c.document
    if (c.loading || d.length() == 0) return
    if (page == "groups") {
        val buildings = d.arrayRows("buildings").map { it.text("code") to it.text("name") }
        var selected by remember { mutableStateOf(buildings.firstOrNull()?.first.orEmpty()) }
        val saved = d.arrayRows("groups").find { it.text("buildingCode") == selected }?.text("conversationId").orEmpty()
        var conversation by remember(selected, saved) { mutableStateOf(saved) }
        AdminCard("楼栋通知群") {
            Text("整点播报和异常巡检发送至对应楼栋群。留空停用该楼通知。")
            AdminChoice("楼栋", selected, buildings, { selected = it }, !c.saving)
            AdminField("钉钉群会话 ID", conversation, { conversation = it }, operate && !c.saving)
            AdminAction("保存通知群", operate && !c.saving && selected.isNotBlank(), "确认更新此楼栋的钉钉通知群？") {
                c.act(path, "PATCH", JSONObject().put("buildingCode", selected).put("conversationId", conversation.trim()))
            }
        }
        return
    }
    val site = d.optJSONObject("site") ?: return
    val permissions = d.optJSONObject("permissions") ?: JSONObject()
    val enabled = operate && !c.saving
    when (page) {
        "site" -> {
            var name by remember(site.toString()) { mutableStateOf(site.text("name")) }
            var icon by remember(site.toString()) { mutableStateOf(site.text("iconUrl")) }
            var school by remember(site.toString()) { mutableStateOf(site.text("schoolLabel")) }
            var schoolId by remember(site.toString()) { mutableStateOf(site.text("schoolId")) }
            var buildings by remember(site.toString()) { mutableStateOf(site.arrayRows("buildings").map { it.copyObject() }) }
            val canEdit = enabled && permissions.optBoolean("manageSite")
            AdminCard("网站与学校") {
                AdminField("网站名称", name, { name = it.take(40) }, canEdit)
                AdminField("网站图标 HTTPS 地址或站内路径", icon, { icon = it.take(500) }, canEdit)
                AdminField("公开学校显示名", school, { school = it.take(60) }, canEdit)
                AdminField("学校 ID（仅后台）", schoolId, { schoolId = it.take(100) }, canEdit)
            }
            AdminCard("住宿楼分类") {
                Text("停用会关闭该楼看板、频道和新预约，历史数据保留。修改代号会影响已有住宿楼绑定。")
                buildings.forEachIndexed { index, row ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun change(key: String, value: Any) { buildings = buildings.mapIndexed { i, b -> if (index == i) b.copyObject().put(key, value) else b } }
                        AdminField("楼栋代号", row.text("code"), { change("code", it.take(32)) }, canEdit)
                        AdminField("楼栋名称", row.text("name"), { change("name", it.take(30)) }, canEdit)
                        AdminToggle("启用", row.optBoolean("enabled", true), { change("enabled", it) }, canEdit)
                        AdminAction("移除此楼栋", canEdit && buildings.size > 1, "移除后，相关用户需要重新选择住宿楼。") { buildings = buildings.filterIndexed { i, _ -> i != index } }
                        HorizontalDivider()
                    }
                }
                AdminAction("添加楼栋", canEdit && buildings.size < 30) { buildings = buildings + JSONObject().put("code", "").put("name", "").put("enabled", true) }
                AdminAction("保存网站与楼栋", canEdit, "确认保存网站和楼栋设置？") {
                    val valid = name.isNotBlank() && buildings.isNotEmpty() && buildings.all { it.text("code").matches(Regex("[a-z][a-z0-9_-]{0,31}")) && it.text("code") != "public" && it.text("name").isNotBlank() } && buildings.map { it.text("code") }.distinct().size == buildings.size && buildings.map { it.text("name").trim() }.distinct().size == buildings.size
                    if (!valid) c.localError("请输入网站名称、有效且不重复的楼栋代号和名称")
                    else c.act(path, "PATCH", JSONObject().put("name", name.trim()).put("iconUrl", icon.trim()).put("schoolLabel", school.trim()).put("schoolId", schoolId.trim()).put("buildings", JSONArray(buildings)))
                }
            }
        }
        "credentials" -> {
            val fields = listOf("appKey" to "App Key", "appSecret" to "App Secret", "robotCode" to "Robot Code", "agentId" to "Agent ID", "aesKey" to "回调 AES Key", "token" to "回调 Token", "webhook" to "群 Webhook", "webhookSecret" to "Webhook 签名密钥")
            val draft = remember { mutableStateMapOf<String,String>() }
            val clear = remember { mutableStateMapOf<String,Boolean>() }
            DisposableEffect(Unit) { onDispose { draft.clear(); clear.clear() } }
            AdminCard("钉钉应用与通知通道") {
                Text("输入新值才覆盖，留空保留。密钥不回显，离开页面即清空。")
                fields.forEach { (key,label) ->
                    Text("$label · ${if (d.optJSONObject("credentials")?.optBoolean(key) == true) "已配置" else "未配置"}")
                    AdminField(label, draft[key].orEmpty(), { draft[key] = it }, enabled && permissions.optBoolean("manageCredentials") && clear[key] != true, secret = true)
                    AdminToggle("清除此配置", clear[key] == true, { clear[key] = it }, enabled && permissions.optBoolean("manageCredentials"))
                }
                AdminAction("保存钉钉配置", enabled && permissions.optBoolean("manageCredentials"), "更改密钥可能影响登录和通知，确认保存？") {
                    val credentials = JSONObject()
                    fields.forEach { (key,_) -> if (clear[key] == true) credentials.put(key, "") else if (!draft[key].isNullOrBlank()) credentials.put(key, draft[key]!!.trim()) }
                    c.act(path, "PATCH", JSONObject().put("credentials", credentials), finished = { draft.clear(); clear.clear() })
                }
            }
        }
        "members" -> {
            var id by remember { mutableStateOf("") }
            var role by remember { mutableStateOf("volunteer") }
            var scopes by remember { mutableStateOf(setOf<String>()) }
            val all = permissions.optBoolean("manageAdmins")
            val volunteer = permissions.optBoolean("manageVolunteers")
            AdminCard("后台成员") {
                d.arrayRows("admins").forEach { row ->
                    AdminFacts(row, listOf("nick" to "昵称", "userId" to "用户 ID", "role" to "身份", "active" to "账号有效"))
                    Text("楼栋：${row.optJSONArray("buildings")?.let { a -> (0 until a.length()).joinToString("、") { a.optString(it) } }.orEmpty()}")
                    AdminAction("移除权限", enabled && (all || volunteer && row.text("role") == "volunteer"), "只移除管理身份，保留用户账号。") { c.act(path, "PATCH", JSONObject().put("removeAdmin", row.text("userId"))) }
                    HorizontalDivider()
                }
                AdminField("真实用户 ID", id, { id = it.take(80) }, enabled && (all || volunteer))
                AdminChoice("授予身份", role, if (all) listOf("super" to "总管", "admin" to "管理员", "operator" to "运维", "volunteer" to "志愿管理") else listOf("volunteer" to "志愿管理"), { role = it }, enabled)
                if (role == "volunteer") site.arrayRows("buildings").forEach { b -> AdminToggle("巡检 ${b.text("name")}", b.text("code") in scopes, { on -> scopes = if (on) scopes + b.text("code") else scopes - b.text("code") }, enabled) }
                AdminAction("添加或更新成员", enabled && (all || volunteer) && id.isNotBlank(), "确认授予此用户所选的管理身份？") {
                    if (role == "volunteer" && scopes.isEmpty()) c.localError("请选择至少一个授权巡检楼栋")
                    else c.act(path, "PATCH", JSONObject().put("admin", JSONObject().put("userId", id.trim()).put("role", role).put("buildings", JSONArray(if (role == "volunteer") scopes.toList() else emptyList<String>()))))
                }
            }
        }
    }
}
