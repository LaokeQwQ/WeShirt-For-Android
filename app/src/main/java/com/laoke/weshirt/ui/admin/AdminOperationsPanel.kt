package com.laoke.weshirt.ui.admin

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.laoke.weshirt.data.AppState
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun AdminOperationsPanel(c: AdminController, state: AppState, operate: Boolean) {
    var page by remember { mutableStateOf("operations") }
    val path = if(page == "diagnostics") "/api/admin/notify-check" else "/api/admin/operations"
    LaunchedEffect(path) { c.load(path) }
    AdminChoice("运营项目",page,listOf("operations" to "整点播报与管理员通知","diagnostics" to "钉钉通知诊断与测试"),{page=it},!c.saving)
    if(page == "diagnostics") { AdminNotificationDiagnostics(c,operate,state.access.optJSONObject("permissions")?.optJSONObject("adminSystem")?.optBoolean("operate") == true); return }
    val broadcast = c.document.optJSONObject("config")?.optJSONObject("broadcast")
    var enabled by remember(broadcast?.toString()) { mutableStateOf(broadcast?.optBoolean("enabled", true) ?: true) }
    var start by remember(broadcast?.toString()) { mutableStateOf((broadcast?.optInt("startHour", 8) ?: 8).toString()) }
    var end by remember(broadcast?.toString()) { mutableStateOf((broadcast?.optInt("endHour", 22) ?: 22).toString()) }
    var hours by remember(broadcast?.toString()) { mutableStateOf(broadcast?.optJSONArray("hours")?.let { a -> (0 until a.length()).joinToString(",") { a.optInt(it).toString() } }.orEmpty()) }
    var filter by remember { mutableStateOf("open") }
    AdminAction("刷新运营信息", !c.saving, "将重新读取服务器设置，尚未保存的播报修改会被重置。") { c.load(path) }
    if (broadcast != null) AdminCard("群定点播报") {
        AdminToggle("启用群播报", enabled, { enabled = it }, operate && !c.saving)
        AdminField("开始小时（北京时间）", start, { start = it }, operate && !c.saving, numeric = true)
        AdminField("结束小时（含该整点）", end, { end = it }, operate && !c.saving, numeric = true)
        AdminField("指定小时（可选，逗号分隔）", hours, { hours = it }, operate && !c.saving)
        Text("默认 8:00–22:00；状态与上次成功播报相同时跳过。留空按开始至结束每小时检查。")
        AdminAction("保存播报设置", operate && !c.saving, "保存后按新的整点计划播报。") {
            val a = start.toIntOrNull(); val b = end.toIntOrNull()
            val parts = hours.trim().split(Regex("[,，\\s]+")).filter(String::isNotBlank)
            val parsed = parts.mapNotNull(String::toIntOrNull).distinct().sorted()
            if (a == null || b == null || a !in 0..23 || b !in 0..23 || a > b || parsed.size != parts.distinct().size || parsed.any { it !in 0..23 }) c.localError("小时须为 0–23 的整数，开始不能晚于结束")
            else c.act(path, "PATCH", JSONObject().put("broadcast", JSONObject().put("enabled", enabled).put("startHour", a).put("endHour", b).put("hours", JSONArray(parsed))))
        }
    }
    AdminChoice("管理员通知", filter, listOf("open" to "待处理", "unread" to "未读", "all" to "全部"), { filter = it })
    c.document.arrayRows("alerts").filter { filter == "all" || if (filter == "unread") it.text("readAt").isBlank() else it.text("resolvedAt").isBlank() }.forEach { alert ->
        AdminCard(alert.text("title").ifBlank { "运营通知" }) {
            AdminFacts(alert, listOf("severity" to "级别", "lastAt" to "最近发生", "count" to "累计次数", "message" to "说明", "resolvedAt" to "解决时间"))
            val channels = alert.optJSONObject("channels") ?: JSONObject()
            listOf("dm" to "管理员私信", "group" to "群消息", "webhook" to "Webhook").forEach { (key, label) -> channels.optJSONObject(key)?.let {
                Text("$label：${if (it.optBoolean("ok")) "已送达" else "未送达，请检查通知日志"}")
                AdminFacts(it,listOf("status" to "状态码","eventId" to "事件"))
            } }
            if (alert.text("readAt").isBlank()) AdminAction("标为已读", operate && !c.saving) { c.act("$path/alerts/${Uri.encode(alert.text("id"))}", "PATCH", JSONObject().put("read", true)) }
            AdminAction(if (alert.text("resolvedAt").isBlank()) "标为已解决" else "重新打开", operate && !c.saving) { c.act("$path/alerts/${Uri.encode(alert.text("id"))}", "PATCH", JSONObject().put("resolved", alert.text("resolvedAt").isBlank())) }
        }
    }
}

@Composable internal fun AdminAssistantPanel(c: AdminController, operate: Boolean) {
    val path = "/api/admin/assistant"
    LaunchedEffect(Unit) { c.load(path) }
    val d = c.document
    var enabled by remember(d.toString()) { mutableStateOf(d.optBoolean("enabled")) }
    var endpoint by remember(d.toString()) { mutableStateOf(d.text("endpoint")) }
    var model by remember(d.toString()) { mutableStateOf(d.text("model")) }
    var daily by remember(d.toString()) { mutableStateOf(d.optInt("dailyLimit", 10).toString()) }
    var minute by remember(d.toString()) { mutableStateOf(d.optInt("minuteLimit", 2).toString()) }
    var key by remember { mutableStateOf("") }; var clear by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { key = "" } }
    AdminAction("刷新模型设置", !c.saving) { c.load(path) }
    if (d.length() > 0) AdminCard("Responses 兼容模型") {
        AdminToggle("启用洗衣助手", enabled, { enabled = it }, operate && !c.saving)
        AdminField("HTTPS 端点", endpoint, { endpoint = it }, operate && !c.saving)
        AdminField("模型名称", model, { model = it }, operate && !c.saving)
        AdminField("每人每日上限（1–20）", daily, { daily = it }, operate && !c.saving, numeric = true)
        AdminField("每人每分钟上限（1–3）", minute, { minute = it }, operate && !c.saving, numeric = true)
        Text("API 密钥：${if (d.optBoolean("keyConfigured")) "已配置" else "未配置"}。原值不会返回，留空保留。")
        AdminField("替换 API 密钥", key, { key = it }, operate && !c.saving, secret = true)
        AdminToggle("清除现有密钥", clear, { clear = it }, operate && !c.saving)
        AdminAction("保存模型设置", operate && !c.saving, "将更新模型调用与频率限制。") {
            val a = daily.toIntOrNull(); val b = minute.toIntOrNull()
            if (a == null || b == null || a !in 1..20 || b !in 1..3 || (endpoint.isNotBlank() && Uri.parse(endpoint).scheme != "https")) c.localError("请检查 HTTPS 端点与调用次数")
            else {
                val body = JSONObject().put("enabled", enabled).put("endpoint", endpoint.trim()).put("model", model.trim()).put("dailyLimit", a).put("minuteLimit", b)
                if (clear) body.put("clearKey", true) else if (key.isNotBlank()) body.put("apiKey", key.trim())
                c.act(path, "PATCH", body, finished = { key = ""; clear = false })
            }
        }
    }
}

internal fun allowedBuildings(state: AppState, resource: String, operation: Boolean = false): List<Pair<String,String>> {
    val p = state.access.optJSONObject("permissions")?.optJSONObject(resource)
    if (p?.optBoolean(if (operation) "operate" else "view") != true) return emptyList()
    val axis = if (operation) "operateBuildings" else "viewBuildings"
    if (!p.has(axis) || !p.isNull(axis) && p.optJSONArray(axis) == null) return emptyList()
    val a = p.optJSONArray(axis)
    val allowed = a?.let { (0 until it.length()).map(it::optString).toSet() }
    return state.buildings.filter { allowed == null || it.code in allowed }.map { it.code to it.name }
}
internal fun globalOperation(state: AppState, resource: String) = state.access.optJSONObject("permissions")?.optJSONObject(resource)?.let { it.optBoolean("operate") && it.has("operateBuildings") && it.isNull("operateBuildings") } == true
internal fun allowedChannel(state: AppState, resource: String, code: String, operation: Boolean = false): Boolean {
    val p = state.access.optJSONObject("permissions")?.optJSONObject(resource) ?: return false
    if (!p.optBoolean(if (operation) "operate" else "view")) return false
    val axis = if (operation) "operateBuildings" else "viewBuildings"
    if (!p.has(axis)) return false
    if (p.isNull(axis)) return true
    val a = p.optJSONArray(axis) ?: return false
    return (0 until a.length()).any { a.optString(it) == code }
}

@Composable internal fun AdminTelemetryPanel(c: AdminController, state: AppState) {
    val choices = allowedBuildings(state, "adminTelemetry")
    val scoped = state.access.optJSONObject("permissions")?.optJSONObject("adminTelemetry")?.optJSONArray("viewBuildings") != null
    var dorm by remember { mutableStateOf(if (scoped) choices.firstOrNull()?.first.orEmpty() else "") }
    var days by remember { mutableStateOf("7") }
    val path = "/api/admin/telemetry?days=$days" + if (dorm.isBlank()) "" else "&dorm=${Uri.encode(dorm)}"
    LaunchedEffect(path) { if (!scoped || dorm.isNotBlank()) c.load(path) }
    AdminChoice("统计周期", days, listOf("1" to "24 小时", "7" to "7 天", "14" to "14 天"), { days = it }, !c.saving)
    AdminChoice("楼栋", dorm, (if (scoped) emptyList() else listOf("" to "全部楼栋")) + choices, { dorm = it }, !c.saving)
    AdminAction("刷新统计", !c.saving && (!scoped || dorm.isNotBlank())) { c.load(path) }
    val d = c.document
    AdminCard("访问与接口") {
        AdminFacts(d, listOf("pageViews" to "页面访问", "errors" to "错误", "received" to "接收事件", "retentionDays" to "保留天数"))
        AdminFacts(d.optJSONObject("api") ?: JSONObject(), listOf("count" to "接口请求", "failed" to "失败", "avgMs" to "平均耗时 ms", "p95Ms" to "95% 耗时 ms"))
        AdminFacts(d.optJSONObject("vitals") ?: JSONObject(), listOf("lcpMs" to "主要内容显示 ms", "inpMs" to "交互延迟 ms", "cls" to "布局偏移"))
    }
    AdminCard("3D 性能") {
        val room = d.optJSONObject("room") ?: JSONObject()
        AdminFacts(room, listOf("avgFps" to "平均帧率", "samples" to "样本", "avgDrawCalls" to "平均绘制调用", "avgTriangles" to "平均三角形"))
        AdminFacts(room.optJSONObject("quality") ?: JSONObject(), listOf("low" to "流畅", "mid" to "均衡", "high" to "精细"))
    }
    d.arrayRows("routes").forEach { AdminCard(it.text("route")) { AdminFacts(it, listOf("views" to "访问量")) } }
    d.arrayRows("recentErrors").forEach { AdminCard("最近错误") { AdminFacts(it, listOf("at" to "时间", "route" to "页面", "kind" to "类型", "status" to "状态码", "eventId" to "事件")) } }
}
