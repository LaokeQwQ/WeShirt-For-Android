package com.laoke.weshirt.ui.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun AdminAuditPanel(c: AdminController, operate: Boolean) {
    var mode by remember { mutableStateOf("logs") }; var type by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("") }; var days by remember { mutableStateOf("7") }; var query by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf(JSONObject()) }; var photo by remember { mutableStateOf<Bitmap?>(null) }
    val resolver = LocalContext.current.contentResolver
    val parameters = "type=${Uri.encode(type)}&level=$level&days=$days&q=${Uri.encode(query.trim())}"
    val path = if (mode == "logs") "/api/admin/audit-logs?$parameters&limit=200" else "/api/admin/urge-audits"
    var exportPath by remember { mutableStateOf("") }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && exportPath.isNotBlank()) c.exportCsv(exportPath) { csv -> withContext(Dispatchers.IO) { resolver.openOutputStream(uri,"w")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("文件无法写入") } }
        exportPath = ""
    }
    LaunchedEffect(mode, type, level, days) { c.load(path); summary = JSONObject(); photo = null }
    DisposableEffect(Unit) { onDispose { photo = null; summary = JSONObject(); exportPath = "" } }
    AdminChoice("审计项目", mode, listOf("logs" to "操作与错误日志", "photos" to "上报事件和凭证"), { mode = it }, !c.saving)
    AdminAction("刷新", !c.saving) { c.load(path) }
    if (mode == "logs") {
        val types = c.document.optJSONObject("types") ?: JSONObject()
        AdminChoice("类型",type,listOf("" to "全部类型") + types.keys().asSequence().map { it to types.text(it) }.toList(),{type=it},!c.saving)
        AdminChoice("级别",level,listOf("" to "全部级别","info" to "普通","warn" to "警告","error" to "错误"),{level=it},!c.saving)
        AdminChoice("时间",days,listOf("1" to "1 天","3" to "3 天","7" to "7 天","30" to "30 天"),{days=it},!c.saving)
        AdminField("关键词 / 事件 ID",query,{query=it.take(100)},!c.saving)
        AdminAction("应用关键词",!c.saving) { c.load(path) }
        AdminAction("导出 CSV（类型 / 级别 / 时间）",!c.saving && !c.loading,"导出按当前类型、级别和天数筛选；服务端导出暂不应用关键词。文件可能含用户资料，请选择合适的保存位置。") { exportPath = "/api/admin/audit-logs.csv?type=${Uri.encode(type)}&level=$level&days=$days"; exportFile.launch("weshirt-audit-${java.time.LocalDate.now()}.csv") }
        Text("共 ${c.document.optInt("total")} 条，当前最多显示 200 条。")
        c.document.arrayRows("events").forEach { event ->
            AdminCard(event.text("action").ifBlank { event.text("type") }) {
                AdminFacts(event,listOf("at" to "时间","id" to "事件 ID","eventId" to "追踪 ID","level" to "级别","type" to "类型","actorNick" to "操作者","actorId" to "操作者账号","target" to "操作对象","ip" to "来源","status" to "状态码","message" to "说明"))
                val detail = event.optJSONObject("detail") ?: JSONObject()
                AdminFacts(detail, listOf("method" to "方法","path" to "请求路径","status" to "状态码","eventId" to "事件","userId" to "用户","machineId" to "设备","dormBuilding" to "楼栋","channel" to "频道","count" to "数量","durationMs" to "耗时 ms","reason" to "原因","message" to "说明","action" to "动作","result" to "结果"))
            }
        }
    } else {
        AdminAction("读取上报统计",!c.saving && !c.loading) { c.inspect("/api/admin/audit-summary") { summary = it } }
        AdminCard("统计") {
            AdminFacts(summary,listOf("auditsToday" to "今日上报","auditsTotal" to "累计上报","messages" to "留言"))
            summary.arrayRows("topMachines").forEach { Text("${it.text("machineId")} · ${it.optInt("count")} 次") }
        }
        AdminAction("清理 30 天前凭证照片",operate && !c.saving,"将永久清理 30 天前的凭证照片，上报事件仍会保留。") { c.act("/api/admin/purge-audit-photos?days=30&apply=1",refresh=path) }
        c.document.arrayRows("items").forEach { audit ->
            AdminCard(audit.text("machineId")) {
                AdminFacts(audit,listOf("auditId" to "事件","at" to "时间","requesterNick" to "申请人","requesterId" to "申请账号","occupierNick" to "被查用户","occupierId" to "被查账号","urgeCount" to "已提醒次数"))
                listOf("occ" to "衣物占用照","around" to "机器周围照").forEach { (kind,label) -> AdminAction(label,!c.saving && !c.loading) {
                    photo = null
                    c.inspect("/api/admin/urge-audit/${Uri.encode(audit.text("auditId"))}/photo/$kind") { result ->
                        val decoded = decodeAuditPhoto(result.text("dataUrl"))
                        if (decoded == null) c.localError("凭证照片格式无效或超出显示大小") else photo = decoded
                    }
                } }
            }
        }
    }
    photo?.let { bitmap -> AlertDialog(onDismissRequest={photo=null},title={Text("上报凭证")},text={Image(bitmap.asImageBitmap(),contentDescription="上报事件凭证照片",modifier=Modifier.fillMaxWidth())},confirmButton={TextButton({photo=null}){Text("关闭")}},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn)) }
}

internal fun decodeAuditPhoto(value: String): Bitmap? = runCatching {
    if (value.length > 3_000_000 || !value.startsWith("data:image/") || !value.substringBefore(',').endsWith(";base64")) return null
    val bytes = Base64.decode(value.substringAfter(','),Base64.DEFAULT)
    if (bytes.size > 2_000_000) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong()*options.outHeight > 80_000_000L) return null
    var sample = 1
    while (options.outWidth/sample > 1600 || options.outHeight/sample > 1600) sample *= 2
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
