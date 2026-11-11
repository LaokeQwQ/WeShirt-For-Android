package com.laoke.weshirt.ui.admin

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.laoke.weshirt.data.AppState
import org.json.JSONObject

@Composable internal fun AdminModerationPanel(c: AdminController, state: AppState, operate: Boolean) {
    var mode by remember { mutableStateOf("reports") }; var channel by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("open") }; var page by remember { mutableIntStateOf(1) }
    val reportsPath = "/api/admin/community-reports?channel=${Uri.encode(channel)}&status=$status&page=$page&limit=20"
    val path = when (mode) { "reports" -> reportsPath; "mutes" -> "/api/moderation/mutes"; else -> "/api/admin/messages" }
    LaunchedEffect(path) { c.load(path) }
    AdminChoice("内容治理", mode, listOf("reports" to "聊天举报", "messages" to "留言管理", "mutes" to "禁言管理"), { mode = it }, !c.saving)
    AdminAction("刷新", !c.saving) { c.load(path) }
    when (mode) {
        "reports" -> {
            Text("举报仅管理员可见，消息被删除后仍保留举报时的文字快照。")
            AdminChoice("频道", channel, listOf("" to "全部授权频道") + (if (allowedChannel(state,"adminMessages","public")) listOf("public" to "公共频道") else emptyList()) + allowedBuildings(state,"adminMessages"), { channel = it; page = 1 }, !c.saving)
            AdminChoice("处理状态", status, listOf("" to "全部") + reportStates, { status = it; page = 1 }, !c.saving)
            c.document.arrayRows("reports").forEach { report -> key(report.text("id")) {
                var resolution by remember(report.toString()) { mutableStateOf(report.text("resolution")) }
                var next by remember(report.toString()) { mutableStateOf(report.text("status")) }
                val canHandle = operate && report.optJSONObject("permissions")?.optBoolean("canHandle") == true
                AdminCard(reportReasons.find { it.first == report.text("reason") }?.second ?: "聊天举报") {
                    AdminFacts(report, listOf("id" to "举报编号", "channel" to "频道", "at" to "举报时间", "status" to "状态", "detail" to "补充"))
                    val snapshot = report.optJSONObject("snapshot") ?: JSONObject()
                    AdminFacts(snapshot, listOf("nick" to "消息作者", "at" to "消息时间", "text" to "被举报内容"))
                    snapshot.optJSONObject("media")?.let { Text("附有${if(it.text("kind") == "video") "视频" else "图片"}，此处不复制附件。") }
                    AdminChoice("处理结果", next, reportStates, { next = it }, canHandle && !c.saving)
                    AdminField("处理说明（完成或驳回时必填）", resolution, { resolution = it.take(300) }, canHandle && !c.saving, multiline = true)
                    AdminAction("保存举报处理", canHandle && !c.saving) {
                        if (next in listOf("resolved","dismissed") && resolution.isBlank()) c.localError("完成或驳回举报时请填写处理说明")
                        else c.act("/api/admin/community-reports/${Uri.encode(report.text("id"))}","PATCH",JSONObject().put("status",next).put("resolution",resolution.trim()),refresh = path)
                    }
                }
            } }
            val pagination = c.document.optJSONObject("pagination") ?: JSONObject()
            Text("第 $page 页 · 共 ${pagination.optInt("total")} 条")
            AdminAction("上一页", !c.saving && page > 1) { page-- }
            AdminAction("下一页", !c.saving && pagination.optBoolean("hasMore")) { page++ }
        }
        "messages" -> {
            val global = globalOperation(state,"adminMessages")
            AdminAction("清空全部留言", global && !c.saving, "将删除全部留言，不可恢复。") { c.act(path,"DELETE",refresh = path) }
            c.document.arrayRows("items").forEach { message -> key(message.text("id")) {
                val mayEdit = operate && allowedChannel(state,"adminMessages",message.text("channel").ifBlank { message.text("dormBuilding") }.ifBlank { "public" },true)
                AdminCard(message.text("nick").ifBlank { "留言" }) {
                    AdminFacts(message, listOf("id" to "留言编号", "at" to "时间", "channel" to "频道", "machineName" to "机器", "text" to "内容"))
                    AdminAction("删除留言", mayEdit && !c.saving, "删除后不可恢复。") { c.act("/api/admin/message/${Uri.encode(message.text("id"))}", "DELETE", refresh = path) }
                    val mayPin = mayEdit && state.access.optJSONObject("permissions")?.optJSONObject("messages")?.optBoolean("operate") == true
                    AdminAction(if(message.optBoolean("pinned")) "取消置顶" else "置顶留言", mayPin && !c.saving, "此操作会改变该频道的置顶内容。") { c.act("/api/messages/${Uri.encode(message.text("id"))}/pin", "POST",JSONObject().put("pinned",!message.optBoolean("pinned")),refresh=path) }
                }
            } }
        }
        else -> {
            AdminMuteForm(c,operate,path)
            c.document.arrayRows("mutes").forEach { mute -> key(mute.text("userId")) {
                AdminCard(mute.text("nick").ifBlank { mute.text("userId") }) {
                    AdminFacts(mute,listOf("userId" to "账号", "reason" to "原因", "at" to "设置时间"))
                    Text(if(mute.text("until").isBlank()) "永久禁言" else "截止：${mute.text("until")}")
                    AdminAction("解除禁言",operate && !c.saving,"解除后对方可以重新发送留言。") { c.act("/api/moderation/mutes/${Uri.encode(mute.text("userId"))}","DELETE",refresh=path) }
                }
            } }
        }
    }
}
private val reportStates = listOf("open" to "待处理", "reviewing" to "处理中", "resolved" to "已处理", "dismissed" to "已驳回")
private val reportReasons = listOf("spam" to "广告或垃圾信息", "abuse" to "辱骂或骚扰", "privacy" to "泄露个人信息", "unsafe" to "不当或危险内容", "other" to "其他原因")

@Composable private fun AdminMuteForm(c:AdminController,operate:Boolean,path:String) {
    var user by remember { mutableStateOf("") }; var duration by remember { mutableStateOf("60") }; var reason by remember { mutableStateOf("") }
    AdminCard("添加禁言") {
        AdminField("用户账号 ID",user,{user=it.trim()},operate && !c.saving)
        AdminChoice("禁言时长",duration,listOf("30" to "30 分钟","60" to "1 小时","1440" to "1 天","10080" to "7 天","permanent" to "永久"),{duration=it},operate && !c.saving)
        AdminField("原因",reason,{reason=it.take(200)},operate && !c.saving,multiline=true)
        Text("权限范围、本人及更高身份限制由服务端校验。")
        AdminAction("确认禁言",operate && !c.saving && user.isNotBlank(),"将限制此用户在频道中发送消息。") {
            c.act(path,body=JSONObject().put("userId",user).put("durationMinutes",if(duration=="permanent") JSONObject.NULL else duration.toInt()).put("reason",reason.trim()),refresh=path,success={user="";reason=""})
        }
    }
}
