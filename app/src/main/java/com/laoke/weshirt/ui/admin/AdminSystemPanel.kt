package com.laoke.weshirt.ui.admin

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.data.AppState
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable internal fun AdminDashboardPanel(c: AdminController, state: AppState, operate: Boolean) {
    var building by remember { mutableStateOf(state.building) }
    val path = "/api/admin/status"
    LaunchedEffect(c) { c.load(path) }
    val data = c.document
    val catalog = data.arrayRows("dormBuildings")
    val selected = catalog.find { it.text("code") == building }
    AdminCard("运行概览") {
        Text("数据模式：${if (data.text("mode") == "real") "公开数据" else if (data.text("mode") == "mock") "测试数据" else "等待更新"}")
        data.optJSONObject("building")?.let { AdminFacts(it, listOf("name" to "站点")) }
        AdminChoice("楼栋", building.takeIf { code -> catalog.any { it.text("code") == code } } ?: "", listOf("" to "全部楼栋") + catalog.map { it.text("code") to it.text("name") }, { building = it }, !c.loading && !c.saving)
        val board = selected ?: data.optJSONObject("board")
        if (board == null) Text("尚无机器快照") else {
            AdminFacts(board, listOf("total" to "机器", "idle" to "空闲", "occupied" to "使用中", "faulty" to "故障", "unknown" to "待更新", "updatedAt" to "快照更新时间"))
            if (selected == null) board.arrayRows("floors").forEach { floor ->
                Text("${floor.text("code")} 层", style = MaterialTheme.typography.labelLarge)
                AdminFacts(floor, listOf("total" to "机器", "idle" to "空闲", "occupied" to "使用中", "faulty" to "故障"))
            }
        }
        AdminAction("刷新概览", !c.loading && !c.saving) { c.load(path) }
    }
    AdminCard("同步状态") {
        val sync = data.optJSONObject("sync")
        if (sync == null) Text("暂无同步记录") else {
            AdminFacts(sync, listOf("lastFetchAt" to "最近拉取", "lastOkAt" to "最近成功", "failCount" to "连续失败次数", "lastSyncAt" to "最近同步"))
            Text(if (sync.text("lastError").isBlank()) "最近同步未记录错误" else "最近同步有错误，可在上游与系统运行诊断")
        }
        val haier = data.optJSONObject("haier")
        if (haier != null) {
            AdminFacts(haier, listOf("poolSize" to "上游账号数", "poolCurrent" to "当前账号序号"))
            haier.arrayRows("poolCooling").forEach { AdminFacts(it, listOf("account" to "冷却账号", "secondsLeft" to "剩余秒数")) }
        }
        AdminAction("立即同步", operate && !c.loading && !c.saving, "将立即拉取机器状态，并可能触发正常的洗衣提醒和通知。确认执行？") { c.act("/api/cron/sync", refresh = path) }
    }
    data.optJSONObject("counts")?.let { counts -> AdminCard("内容与用户") { AdminFacts(counts, listOf("users" to "用户", "messages" to "留言", "notices" to "提醒")) } }
    data.optJSONObject("dingtalk")?.let { ding -> AdminCard("钉钉配置状态") {
        listOf("configured" to "应用登录", "robot" to "机器人", "webhook" to "群通知入口", "callback" to "回调校验").forEach { (key, label) -> Text("$label：${if (ding.optBoolean(key)) "已配置" else "未配置"}") }
        Text("配置完整不代表消息可达，可在上游与系统测试通知。", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable internal fun AdminSystemPanel(c: AdminController, operate: Boolean) {
    var section by remember { mutableStateOf("accounts") }
    val path = when (section) { "notifications" -> "/api/admin/notify-check"; "diagnostics" -> "/api/admin/diag-haier"; else -> "/api/admin/accounts" }
    LaunchedEffect(c, path) { c.load(path) }
    AdminChoice("管理项目", section, listOf("accounts" to "上游账号", "notifications" to "钉钉通知诊断", "diagnostics" to "上游连接诊断"), { section = it }, !c.saving && !c.loading)
    when (section) {
        "accounts" -> AdminUpstreamAccounts(c, operate)
        "notifications" -> AdminNotificationDiagnostics(c, operate)
        "diagnostics" -> AdminConnectionDiagnostics(c, operate)
    }
}

@Composable private fun AdminUpstreamAccounts(c: AdminController, operate: Boolean) {
    var selected by remember { mutableStateOf("1") }
    var token by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf("") }
    var deadline by remember { mutableLongStateOf(0L) }
    var importResult by remember { mutableStateOf("") }
    val seconds by produceState(0, deadline) {
        do {
            value = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
            if (value > 0) delay(1000)
        } while (value > 0)
    }
    DisposableEffect(Unit) { onDispose { token = ""; code = ""; imported = ""; phone = "" } }
    val accounts = c.document.arrayRows("accounts")
    val enabled = operate && !c.loading && !c.saving
    val options = accounts.map { it.text("index") to "#${it.text("index")} ${it.text("label")}" }
    val selectedIndex = selected.toIntOrNull()?.takeIf { index -> accounts.any { it.optInt("index") == index } }
    AdminCard("账号池") {
        AdminFacts(c.document, listOf("size" to "账号数", "current" to "当前账号序号", "probedAt" to "最近检测时间"))
        if (accounts.isEmpty()) Text("尚未读取到账号")
        accounts.forEach { account ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${account.text("index")} ${account.text("label")}", style = MaterialTheme.typography.titleSmall)
                val alive = if (account.isNull("alive") || !account.has("alive")) "未检测" else if (account.optBoolean("alive")) "可用" else "不可用"
                Text("存活：$alive · ${if (account.optBoolean("expired")) "授权已过期" else if (account.isNull("daysLeft")) "有效期未知" else "剩余 ${account.optInt("daysLeft")} 天"}")
                AdminFacts(account, listOf("coolingSec" to "冷却秒数", "probeStatus" to "检测状态码", "probedAt" to "最近检测"))
                Text("来源：${if (account.text("source") == "kv") "后台保存" else "云端配置"} · ${if (account.optBoolean("hasToken")) "已有授权" else "缺少授权"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        AdminAction("刷新账号", !c.loading && !c.saving) { c.load("/api/admin/accounts") }
        AdminAction("检测账号存活", enabled) { c.act("/api/admin/accounts/probe", refresh = "/api/admin/accounts") }
    }
    if (!operate) return
    AdminCard("更新授权") {
        AdminChoice("目标账号", selected, options, { selected = it; token = ""; label = "" }, enabled && accounts.isNotEmpty())
        AdminField("备注（最多 16 字）", label, { label = it.take(16) }, enabled)
        AdminField("新授权 token", token, { token = it }, enabled, secret = true, multiline = true)
        Text("授权只提交到服务端，保存后清空；已有授权不会在此回显。", style = MaterialTheme.typography.bodySmall)
        AdminAction("保存新授权", enabled && selectedIndex != null && token.isNotBlank(), "将替换所选账号的授权，下次同步即使用新授权。确认保存？") {
            selectedIndex?.let { index -> c.act("/api/admin/accounts", "PUT", JSONObject().put("index", index).put("token", token.trim()).put("label", label.trim()), refresh = "/api/admin/accounts", finished = { token = ""; label = "" }) }
        }
        AdminAction("清空输入", !c.saving && (token.isNotBlank() || label.isNotBlank())) { token = ""; label = "" }
    }
    AdminCard("短信登录上游账号") {
        Text("使用本人账号接收验证码。服务端需已配置上游短信登录接口。", style = MaterialTheme.typography.bodySmall)
        AdminChoice("写入账号", selected, options, { selected = it; code = "" }, enabled && accounts.isNotEmpty())
        AdminField("手机号", phone, { phone = it.filter(Char::isDigit).take(11) }, enabled, numeric = true)
        AdminAction(if (seconds > 0) "${seconds} 秒后可重发" else "发送验证码", enabled && seconds == 0 && Regex("1\\d{10}").matches(phone), "将向填写的手机号发送一条上游登录短信。确认发送？") {
            c.act("/api/admin/accounts/send-code", body = JSONObject().put("phone", phone), refresh = null, success = { result -> deadline = SystemClock.elapsedRealtime() + result.optInt("waitSec", 60).coerceIn(1, 3600) * 1000L })
        }
        AdminField("短信验证码", code, { code = it.filter(Char::isDigit).take(8) }, enabled, secret = true, numeric = true)
        AdminAction("登录并写入账号", enabled && selectedIndex != null && Regex("1\\d{10}").matches(phone) && code.isNotBlank(), "将使用此验证码登录，并把授权写入所选账号。确认继续？") {
            selectedIndex?.let { index -> c.act("/api/admin/accounts/login", body = JSONObject().put("phone", phone).put("code", code).put("index", index), refresh = "/api/admin/accounts", finished = { code = ""; phone = "" }) }
        }
    }
    AdminCard("批量导入授权") {
        Text("一行一个 token，可写为“标签, Bearer token”。最多 20 个；导入后以这批账号为准。", style = MaterialTheme.typography.bodySmall)
        AdminField("待导入授权", imported, { imported = it; importResult = "" }, enabled, secret = true, multiline = true)
        AdminAction("替换账号池", enabled && imported.isNotBlank(), "将用本次导入替换账号池，并清除旧的按序号覆盖和轮换状态。确认导入？") {
            c.act("/api/admin/accounts/import", body = JSONObject().put("text", imported.trim()), refresh = "/api/admin/accounts", finished = { imported = "" }, success = { result -> importResult = "已导入 ${result.optInt("imported")} 个账号，跳过 ${result.optJSONArray("skipped")?.length() ?: 0} 个" })
        }
        if (importResult.isNotBlank()) Text(importResult)
        AdminAction("清空导入内容", !c.saving && imported.isNotBlank()) { imported = "" }
    }
}

@Composable internal fun AdminNotificationDiagnostics(c: AdminController, operate: Boolean, systemOperate: Boolean = operate) {
    var result by remember { mutableStateOf<JSONObject?>(null) }
    val data = c.document
    val busy = c.saving || c.loading
    AdminCard("通道检查") {
        data.optJSONObject("configured")?.let { configured ->
            listOf("appKey" to "应用标识", "appSecret" to "应用密钥", "robotCode" to "机器人", "webhook" to "群通知入口", "adminDingUserId" to "测试收件人").forEach { (key, title) -> Text("$title：${if (configured.optBoolean(key)) "已配置" else "未配置"}") }
        }
        val channels = data.optJSONObject("channels")
        channels?.optJSONObject("dm")?.let { dm -> Text("私聊通道：${if (dm.optBoolean("ready")) "已就绪" else "未就绪"}"); AdminFacts(dm, listOf("boundUsers" to "已绑定用户")) }
        channels?.optJSONObject("group")?.let { group ->
            Text("群消息通道：${if (group.optBoolean("ready")) "已就绪" else "未就绪"}")
            Text("整点播报：${if (group.optBoolean("automatic")) "已启用" else "未启用"}")
            Text("发送方式：${when(group.text("via")) { "webhook" -> "群机器人"; "conversation" -> "群会话"; else -> "未配置" }}")
            group.arrayRows("buildings").forEach { Text("播报楼栋：${it.text("name")}") }
            AdminFacts(group, listOf("updatedAt" to "群通道更新时间"))
        }
        Text("整点播报按楼栋配置，默认 8:00–22:00；状态未变时不发。", style = MaterialTheme.typography.bodySmall)
        AdminAction("刷新通道检查", !busy) { c.load("/api/admin/notify-check") }
    }
    AdminCard("回调统计") {
        val callbacks = data.optJSONObject("callbacks")
        listOf("dm" to "私聊", "group" to "群聊").forEach { (key, name) -> callbacks?.optJSONObject(key)?.let { Text(name, style = MaterialTheme.typography.labelLarge); AdminFacts(it, listOf("count" to "回调次数", "lastAt" to "最近回调")) } }
        data.optJSONObject("lastCallback")?.let { AdminFacts(it, listOf("at" to "最后回调", "status" to "状态码", "statusCode" to "状态码")) }
        AdminAction("清空回调统计", systemOperate && !busy, "将清除回调计数和最近回调记录，保留群会话绑定。确认清空？") { c.act("/api/admin/reset-callback-stats", refresh = "/api/admin/notify-check") }
        AdminAction("清空统计与群会话", systemOperate && !busy, "将同时移除记录的群会话，依赖该会话的群通知需重新绑定。确认执行？") { c.act("/api/admin/reset-callback-stats?group=1", refresh = "/api/admin/notify-check") }
    }
    AdminCard("发送测试通知") {
        Text("会发送真实的取衣提醒样例，不会创建洗衣任务。", style = MaterialTheme.typography.bodySmall)
        AdminAction("发送私聊测试", operate && !busy, "将向服务端配置的管理员测试账号发送一条真实钉钉通知。确认发送？") { c.act("/api/admin/notify-test?to=self&sample=take", refresh = "/api/admin/notify-check", success = { result = it }) }
        AdminAction("发送群聊测试", operate && !busy, "将向服务端已绑定的测试群通道发送一条真实钉钉消息。确认发送？") { c.act("/api/admin/notify-test?to=group&sample=take", refresh = "/api/admin/notify-check", success = { result = it }) }
        result?.let { report -> listOf("dm" to "私聊", "group" to "群聊").forEach { (key, title) -> report.optJSONObject(key)?.let { channel ->
            Text("$title 测试：${if (channel.optBoolean("ok")) "已发送" else "发送失败"}", color = if (channel.optBoolean("ok")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (!channel.optBoolean("ok")) Text("请检查通道配置与服务端通知日志。", style = MaterialTheme.typography.bodySmall)
        } } }
    }
}

@Composable private fun AdminConnectionDiagnostics(c: AdminController, operate: Boolean) {
    var account by remember { mutableStateOf("") }
    var scheduleEvery by remember { mutableStateOf("") }
    val data = c.document
    val busy = c.saving || c.loading
    AdminCard("上游连接诊断") {
        Text("检查云端到上游的连接；结果仅显示端点类别、状态码和耗时。", style = MaterialTheme.typography.bodySmall)
        Text("数据模式：${when(data.text("mode")) { "real" -> "公开数据"; "mock" -> "测试数据"; else -> "等待诊断" }}")
        AdminField("指定账号序号（留空使用默认账号）", account, { account = it.filter(Char::isDigit).take(3) }, !busy, numeric = true)
        AdminAction("运行连接诊断", !busy) {
            val value = account.toIntOrNull()
            if (account.isNotBlank() && (value == null || value < 1)) c.localError("账号序号应为正整数")
            else c.load("/api/admin/diag-haier" + if (value == null) "" else "?account=$value")
        }
        data.arrayRows("steps").forEach { step ->
            val name = when (step.text("label")) { "floorCodeList" -> "楼层目录"; "positionDevice" -> "设备目录"; "deviceDetailPage" -> "设备状态"; else -> "上游请求" }
            Text(name, style = MaterialTheme.typography.labelLarge)
            Text(if (step.optBoolean("ok")) "连接成功" else "连接未完成", color = if (step.optBoolean("ok")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            AdminFacts(step, listOf("status" to "HTTP 状态码", "ms" to "耗时（毫秒）"))
            if (step.text("error").isNotBlank()) Text("请求发生连接或超时错误", style = MaterialTheme.typography.bodySmall)
        }
        data.optJSONObject("pool")?.let { pool -> AdminFacts(pool, listOf("size" to "账号数", "current" to "当前账号")); pool.arrayRows("cooling").forEach { AdminFacts(it, listOf("account" to "冷却账号", "secondsLeft" to "冷却剩余秒数")) } }
        if (data.arrayRows("steps").isEmpty() && !c.loading) Text("暂无出网步骤；请检查数据模式与云端上游配置。", style = MaterialTheme.typography.bodySmall)
    }
    AdminCard("定时同步检查") {
        Text("同步负责状态与洗衣提醒，应由云端定时器持续触发。", style = MaterialTheme.typography.bodySmall)
        AdminAction("检查定时同步建议", operate && !busy) { c.inspect("/api/admin/sync-url") { scheduleEvery = it.text("every") } }
        if (scheduleEvery.isNotBlank()) Text("建议间隔：$scheduleEvery。请在云端检查定时器最近的执行记录。")
    }
}
