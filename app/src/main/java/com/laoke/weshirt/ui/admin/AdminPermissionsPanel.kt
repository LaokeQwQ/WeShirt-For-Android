package com.laoke.weshirt.ui.admin

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.laoke.weshirt.data.AppState
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.scope(key: String): Set<String>? = if (isNull(key)) null else optJSONArray(key)?.let { a -> (0 until a.length()).map(a::optString).toSet() }.orEmpty()
private fun within(requested: Set<String>?, ceiling: Set<String>?): Set<String>? = when {
    ceiling == null -> requested
    requested == null -> ceiling
    else -> requested.intersect(ceiling)
}

@Composable internal fun AdminPermissionsPanel(c: AdminController, state: AppState) {
    if (state.access.optJSONObject("identity")?.text("role") != "super") { Text("仅总管可编辑成员权限"); return }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    fun route() = "/api/admin/permissions?q=${c.api.encoded(query.trim())}&userId=${c.api.encoded(target)}"
    LaunchedEffect(Unit) { c.load(route()) }
    AdminCard("成员功能权限与范围") {
        Text("身份决定能力上限；每项操作范围须包含在查看范围内。")
        AdminField("昵称或用户 ID", query, { query = it.take(80) }, !c.saving)
        AdminAction("搜索成员", !c.saving) { target = ""; c.load(route()) }
        c.document.arrayRows("users").forEach { user -> AdminAction("${user.text("nick")} · ${user.text("role")} · ${user.text("id")}", !c.saving) { target = user.text("id"); c.load(route()) } }
    }
    val selected = c.document.optJSONObject("selected") ?: return
    val user = selected.optJSONObject("user") ?: return
    val base = selected.optJSONObject("base") ?: return
    val original = selected.optJSONObject("permissions") ?: return
    var draft by remember(user.text("id"), original.toString()) { mutableStateOf(original.copyObject()) }
    val editable = user.text("role") != "super" && !c.saving && c.document.optBoolean("canManage")
    val resources = c.document.arrayRows("resources")
    val buildings = listOf("public" to "公共频道") + c.document.arrayRows("buildings").map { it.text("code") to it.text("name") }
    AdminCard("${user.text("nick")}的权限") {
        if (!editable && user.text("role") == "super") Text("总管权限不能设置个人覆盖。")
        resources.forEach { resource ->
            val key = resource.text("key")
            val ceiling = base.optJSONObject(key) ?: JSONObject()
            val row = draft.optJSONObject(key) ?: ceiling.copyObject()
            fun change(next: JSONObject) { draft = draft.copyObject().put(key, next) }
            Text(resource.text("label"), style = MaterialTheme.typography.titleSmall)
            AdminToggle("允许查看", row.optBoolean("view"), { on -> change(row.copyObject().put("view", on).apply { if (!on) put("operate", false) }) }, editable && ceiling.optBoolean("view"))
            AdminToggle("允许操作", row.optBoolean("operate"), { on -> change(row.copyObject().put("operate", on)) }, editable && ceiling.optBoolean("operate") && row.optBoolean("view"))
            if (resource.optBoolean("scoped") && row.optBoolean("view")) {
                val axes = listOf("viewBuildings" to "可查看楼栋") + if (row.optBoolean("operate")) listOf("operateBuildings" to "可操作楼栋") else emptyList()
                axes.forEach { (axis,label) ->
                    val allowed = if (axis == "operateBuildings") within(ceiling.scope(axis), row.scope("viewBuildings")) else ceiling.scope(axis)
                    val current = row.scope(axis)
                    Text(label)
                    fun setScope(codes: Set<String>?) {
                        val next = row.copyObject().put(axis, codes?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                        if (axis == "viewBuildings") next.put("operateBuildings", within(next.scope("operateBuildings"), codes)?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                        change(next)
                    }
                    AdminToggle("全部楼栋", current == null, { on -> setScope(if (on) null else emptySet()) }, editable && allowed == null)
                    if (current != null) buildings.forEach { (code,name) -> AdminToggle(name, code in current, { on -> setScope(if (on) current + code else current - code) }, editable && (allowed == null || code in allowed)) }
                }
            }
            HorizontalDivider()
        }
        AdminAction("保存成员权限", editable, "确认更新此用户的查看、操作权限和楼栋范围？") { c.act("/api/admin/permissions/${c.api.encoded(user.text("id"))}", "PATCH", JSONObject().put("permissions", draft.copyObject())) }
        AdminAction("恢复身份默认权限", editable, "删除此用户的个别权限覆盖，恢复当前身份默认权限。") { c.act("/api/admin/permissions/${c.api.encoded(user.text("id"))}", "DELETE") }
    }
}
