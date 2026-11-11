package com.laoke.weshirt.ui.admin

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.laoke.weshirt.data.AppState
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun AdminAnnouncementsPanel(c: AdminController, state: AppState, operate: Boolean) {
    val path = "/api/admin/announcements"
    LaunchedEffect(Unit) { c.load(path) }
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var start by remember { mutableStateOf("") }; var end by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    fun edit(row: JSONObject) { draft = row.copyObject(); start = localTime(row.text("startsAt")); end = localTime(row.text("endsAt")); preview = false }
    val choices = allowedBuildings(state, "adminAnnouncements", operation = true)
    val viewChoices = allowedBuildings(state, "adminAnnouncements")
    val all = globalOperation(state, "adminAnnouncements")
    AdminAction("刷新公告列表", !c.saving, if (draft == null) null else "刷新列表不会覆盖当前编辑内容。") { c.load(path) }
    AdminAction("新建公告", operate && !c.saving, if (draft == null) null else "将放弃当前尚未保存的编辑。") { edit(JSONObject().put("title", "").put("markdown", "# 公告\n\n请填写公告内容。").put("published", false).put("dormBuilding", if (all) "" else choices.firstOrNull()?.first.orEmpty())) }
    c.document.arrayRows("announcements").forEach { row ->
        AdminAction("${if (row.optBoolean("published")) "已发布" else "草稿"} · ${row.text("title")} · v${row.optInt("version",1)}", !c.saving, if (draft == null) null else "将切换公告并放弃当前未保存编辑。") { edit(row) }
    }
    draft?.let { selected ->
        val canEdit = operate && (if (selected.text("dormBuilding").isBlank()) all else choices.any { it.first == selected.text("dormBuilding") })
        fun update(key: String, value: Any) { draft = selected.copyObject().put(key, value) }
        AdminCard("编辑公告") {
            AdminField("标题", selected.text("title"), { update("title", it.take(100)) }, canEdit && !c.saving)
            AdminChoice("可见楼栋", selected.text("dormBuilding"), (if (all) listOf("" to "全部楼栋") else emptyList()) + (if (canEdit) choices else viewChoices), { update("dormBuilding", it) }, canEdit && !c.saving)
            AdminToggle("发布", selected.optBoolean("published"), { update("published", it) }, canEdit && !c.saving)
            AdminField("开始时间（可选，2026-11-11 08:00）", start, { start = it }, canEdit && !c.saving)
            AdminField("结束时间（可选）", end, { end = it }, canEdit && !c.saving)
            Text("时间使用手机当前时区；留空表示不限。发布新版本后，不再提醒设置会重新生效。")
            if (canEdit) {
                val tools = listOf("标题" to "\n## 标题\n", "粗体" to "**粗体文字**", "斜体" to "*斜体文字*", "列表" to "\n- 列表项\n", "引用" to "\n> 引用\n", "链接" to "[说明](https://example.com)", "代码" to "\n```\n代码\n```\n", "分隔线" to "\n\n---\n\n")
                tools.chunked(3).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        group.forEach { (label, text) ->
                            TextButton({ update("markdown", selected.text("markdown") + text) }, enabled = !c.saving) { Text(label) }
                        }
                    }
                }
            }
            AdminToggle("预览", preview, { preview = it })
            if (preview) AdminMarkdownPreview(selected.text("markdown")) else AdminField("Markdown 正文", selected.text("markdown"), { update("markdown", it.take(20000)) }, canEdit && !c.saving, multiline = true)
            AdminAction(if (selected.optBoolean("published")) "保存并发布" else "保存草稿", canEdit && !c.saving, "确认保存这条公告？") {
                try {
                    val a = parseTime(start); val b = parseTime(end)
                    if (selected.text("title").isBlank() || selected.text("markdown").isBlank()) throw IllegalArgumentException("请填写标题和正文")
                    if (a != null && b != null && !Instant.parse(b).isAfter(Instant.parse(a))) throw IllegalArgumentException("结束时间必须晚于开始时间")
                    val body = selected.copyObject().put("dormBuilding", selected.text("dormBuilding").ifBlank { null } ?: JSONObject.NULL).put("startsAt", a ?: JSONObject.NULL).put("endsAt", b ?: JSONObject.NULL)
                    c.act(path, body = body, success = { draft = null })
                } catch (e: Exception) { c.localError(e.message?.takeIf { e is IllegalArgumentException } ?: "时间格式不正确，请使用 年-月-日 时:分") }
            }
            if (selected.text("id").isNotBlank()) AdminAction("删除公告", canEdit && !c.saving, "公告删除后不可恢复。") { c.act("$path/${Uri.encode(selected.text("id"))}", "DELETE", success = { draft = null }) }
            AdminAction("关闭编辑", !c.saving, "放弃尚未保存的内容？") { draft = null }
        }
    }
}

private val adminTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun localTime(value: String) = runCatching { adminTimeFormatter.format(Instant.parse(value).atZone(ZoneId.systemDefault())) }.getOrDefault("")
private fun parseTime(value: String): String? = if (value.isBlank()) null else LocalDateTime.parse(value.trim().replace('T', ' '), adminTimeFormatter).atZone(ZoneId.systemDefault()).toInstant().toString()

/** Native preview. Links and remote image fetching are disabled in the admin draft. */
@Composable internal fun AdminMarkdownPreview(text: String) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface
    val markwon = remember(context) { io.noties.markwon.Markwon.builder(context)
        .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
        .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
        .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() { override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) { builder.linkResolver { _, _ -> } } }).build() }
    AndroidView(factory = { android.widget.TextView(it).apply { textSize = 16f; setLineSpacing(6f, 1.1f) } }, modifier = Modifier.fillMaxWidth(), update = { view -> view.setTextColor(android.graphics.Color.argb(255,(color.red*255).toInt(),(color.green*255).toInt(),(color.blue*255).toInt())); markwon.setMarkdown(view, text) })
}
