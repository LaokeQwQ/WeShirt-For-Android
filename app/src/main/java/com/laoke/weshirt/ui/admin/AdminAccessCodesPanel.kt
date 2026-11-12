package com.laoke.weshirt.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.laoke.weshirt.data.AppState
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.codeScope(key: String): Set<String>? = if (isNull(key)) null else optJSONArray(key)?.let { a -> (0 until a.length()).map(a::optString).toSet() }.orEmpty()

@Composable internal fun AdminAccessCodesPanel(c: AdminController, state: AppState) {
    if (state.access.optJSONObject("identity")?.text("role") != "super") { Text("仅总管可管理临时口令"); return }
    val path = "/api/admin/access-codes"
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var oneTimeCode by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("12") }
    var draft by remember { mutableStateOf(JSONObject()) }
    val creation = remember(c) { AdminCodeCreation() }
    val busy = c.loading || c.saving
    DisposableEffect(c) { onDispose { oneTimeCode = ""; name = ""; note = ""; draft = JSONObject(); creation.clear() } }
    LaunchedEffect(c) { c.load(path) }
    val base = c.document.optJSONObject("base") ?: JSONObject()
    val buildings = listOf("public" to "公共频道") + c.document.arrayRows("buildings").filter { it.text("code") != "public" }.map { it.text("code") to it.text("name") }
    AdminCard("临时管理员口令") {
        Text("默认拥有完整管理员权限，可缩小功能与楼栋范围；不包含总管权限。默认 12 小时，最长 30 天。")
        AdminAction("创建临时口令", !busy && base.length() > 0) { name = ""; note = ""; hours = "12"; draft = base.copyObject(); creation.clear(); creating = true }
        AdminAction("刷新口令列表", !busy) { c.load(path) }
        if (!busy && c.document.arrayRows("codes").isEmpty()) Text("暂无临时口令")
        c.document.arrayRows("codes").forEach { row -> AdminCard(row.text("name")) {
            val expired = row.optLong("expiresAt") <= System.currentTimeMillis()
            val revoked = row.optLong("revokedAt") > 0
            Text("${row.text("prefix")}…${row.text("suffix")}")
            Text(if (revoked) "已销毁" else if (expired) "已过期" else "有效")
            if (row.text("note").isNotBlank()) Text(row.text("note"))
            Text("到期：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.optLong("expiresAt")))}", style = MaterialTheme.typography.bodySmall)
            if (!revoked && !expired) AdminAction("销毁口令及会话", !busy, "使用此口令建立的全部会话会立即失效，且无法恢复。确认销毁？") { c.act("$path/${c.api.encoded(row.text("id"))}", "DELETE", refresh = path) }
        } }
    }
    if (creating) AlertDialog(onDismissRequest = { if (!c.saving) creating = false },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("创建临时口令") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AdminField("名称", name, { name = it.take(60) }, !c.saving)
            AdminField("备注", note, { note = it.take(240) }, !c.saving, multiline = true)
            AdminField("有效时长（小时，最多 720）", hours, { hours = it.take(8) }, !c.saving)
            c.document.arrayRows("resources").forEach { resource ->
                val key = resource.text("key")
                val ceiling = base.optJSONObject(key) ?: JSONObject()
                val row = draft.optJSONObject(key) ?: ceiling.copyObject()
                fun change(value: JSONObject) { draft = draft.copyObject().put(key, value) }
                Text(resource.text("label"), style = MaterialTheme.typography.titleSmall)
                AdminToggle("查看", row.optBoolean("view"), { enabled -> change(row.copyObject().put("view", enabled).apply { if (!enabled) put("operate", false) }) }, !c.saving && ceiling.optBoolean("view"))
                AdminToggle("操作", row.optBoolean("operate"), { change(row.copyObject().put("operate", it)) }, !c.saving && ceiling.optBoolean("operate") && row.optBoolean("view"))
                if (resource.optBoolean("scoped") && row.optBoolean("view")) {
                    val axes = listOf("viewBuildings" to "可查看楼栋") + if (row.optBoolean("operate")) listOf("operateBuildings" to "可操作楼栋") else emptyList()
                    axes.forEach { (axis, label) ->
                        val current = row.codeScope(axis)
                        val viewing = row.codeScope("viewBuildings")
                        fun setScope(value: Set<String>?) {
                            val next = row.copyObject().put(axis, value?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                            if (axis == "viewBuildings" && value != null) next.put("operateBuildings", JSONArray((row.codeScope("operateBuildings") ?: value).intersect(value).toList()))
                            change(next)
                        }
                        Text(label)
                        AdminToggle("全部楼栋", current == null, { setScope(if (it) null else emptySet()) }, !c.saving && (axis != "operateBuildings" || viewing == null))
                        if (current != null) buildings.filter { axis != "operateBuildings" || viewing == null || it.first in viewing }.forEach { (code, title) ->
                            AdminToggle(title, code in current, { setScope(if (it) current + code else current - code) }, !c.saving)
                        }
                    }
                }
                HorizontalDivider()
            }
        } },
        confirmButton = { TextButton(onClick = {
            val ttl = hours.toDoubleOrNull()
            if (name.isBlank() || ttl == null || !ttl.isFinite() || ttl <= 0 || ttl > 720) c.localError("请填写名称及 0–720 小时内的有效时长")
            else {
                val body = JSONObject().put("name", name.trim()).put("note", note.trim()).put("ttlHours", ttl).put("permissions", draft.copyObject())
                c.act(path, body = body, key = creation.keyFor(body.toString()), refresh = path, success = { result -> oneTimeCode = result.text("code"); copied = false; creating = false; creation.clear() })
            }
        }, enabled = !c.saving && name.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton({ creating = false }, enabled = !c.saving) { Text("取消") } })
    if (oneTimeCode.isNotBlank()) AlertDialog(onDismissRequest = { oneTimeCode = ""; copied = false },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("口令仅显示这一次") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("关闭后只能查看开头和结尾。请保存到可信位置，遗失后需销毁重建。")
            SelectionContainer { Text(oneTimeCode, style = MaterialTheme.typography.bodySmall) }
            if (copied) Text("已复制，请妥善保存")
        } },
        confirmButton = { TextButton({
            val clip = ClipData.newPlainText("WeShirt 临时管理员口令", oneTimeCode)
            if (Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            copied = true
        }) { Text("复制口令") } }, dismissButton = { TextButton({ oneTimeCode = ""; copied = false }) { Text("已保存，关闭") } })
}
