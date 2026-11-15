package com.laoke.weshirt.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun JSONObject.str(key: String, default: String = "") = optString(key, default).takeUnless { it == "null" } ?: default
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun epoch(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
data class Building(val code: String, val name: String)
data class PendingShare(val origin: String, val kind: String, val ticket: String)
data class Machine(val id: String, val name: String, val slot: String, val kind: String, val state: String, val finishAt: Long?, val note: String, val disinfectant: Boolean, val selfCleaned: Boolean, val health: String, val occupantId: String = "", val suspectedUncollected: Boolean = false, val estimated: Boolean = false, val floorCode: String = "", val floorName: String = "", val originalName: String = "", val remarkName: String = "", val clockOffsetMs: Long = 0) {
    fun minutes(now: Long = System.currentTimeMillis()) = finishAt?.let { ((it - (now + clockOffsetMs) + 59999) / 60000).coerceAtLeast(0) }
    fun availability(now: Long) = if(suspectedUncollected) Long.MAX_VALUE - 2 else when (state) { "idle" -> 0L; "occupied", "abnormal" -> localFinishAt()?.takeIf { it > now } ?: Long.MAX_VALUE - 1; else -> Long.MAX_VALUE }
    val stateLabel get() = if(suspectedUncollected) "疑似未取" else when (state) { "idle" -> "空闲"; "occupied" -> "使用中"; "abnormal" -> "待复核"; "faulty" -> "暂不可用"; else -> "状态待更新" }
    companion object { fun parse(j: JSONObject) = Machine(j.str("id"), j.str("displayName", j.str("name")), j.str("slot"), j.str("kind", "washer"), j.str("state", "unknown"), epoch(j.str("finishAt")), j.optJSONObject("occupancy")?.str("note") ?: j.str("note"), j.optBoolean("useDisinfectant") || j.optJSONObject("occupancy")?.optBoolean("useDisinfectant") == true, j.optBoolean("selfCleaned") || j.optJSONObject("occupancy")?.optBoolean("selfCleaned") == true, j.optJSONObject("health")?.str("label") ?: "", occupantId = j.optJSONObject("occupancy")?.str("userId").orEmpty(), suspectedUncollected = j.optBoolean("suspectedUncollected") || j.optJSONObject("occupancy")?.optBoolean("suspectedUncollected") == true, estimated = j.optBoolean("estimated"), floorCode = j.str("floorCode"), floorName = j.str("floorName"), originalName = j.str("originalName", j.str("name")), remarkName = j.str("remarkName")) }
}
data class LaundryTask(val id: String, val machineId: String, val name: String, val kind: String, val endAt: Long?, val done: Boolean, val note: String, val disinfectant: Boolean, val selfCleaned: Boolean, val estimated: Boolean, val startAt: Long? = null, val createdAt: Long? = null, val archived: Boolean = false, val expiresAt: Long? = null, val timingSource: String = "user_report", val completionConfirmed: Boolean = false, val timeOffsetMinutes: Int = 0, val clockOffsetMs: Long = 0) {
    companion object { fun parse(j: JSONObject) = LaundryTask(j.str("id"), j.str("machineId"), j.str("machineName"), j.str("kind"), epoch(j.str("endAt")), j.optBoolean("done"), j.str("note"), j.optBoolean("useDisinfectant"), j.optBoolean("selfCleaned"), j.optBoolean("estimated"), epoch(j.str("startAt")), epoch(j.str("createdAt")), j.optBoolean("archived"), epoch(j.str("expiresAt")), j.str("timingSource", "user_report"), j.optBoolean("completionConfirmed"), j.optInt("timeOffsetMinutes")) }
}
data class Profile(val id: String, val nick: String, val avatarUrl: String, val building: String, val changesLeft: Int, val role: String,
                   val dingNick: String = "", val mobile: String = "", val notifyOn: Boolean = true, val notifyLeadMin: Int = 0) {
    companion object { fun parse(j: JSONObject) = Profile(j.str("id"), j.str("nick", "未设置昵称"), j.str("avatarUrl"), j.str("dormBuilding"), j.optInt("dormChangesRemaining", 1), j.str("role", "user"), j.str("dingNick"), j.str("mobile"), j.optBoolean("notifyOn", true), j.optInt("notifyLeadMin")) }
}
data class Channel(val code: String, val name: String, val avatar: String, val mentionAll: Boolean,
                   val canAnnounce: Boolean = false, val canManageAvatar: Boolean = false,
                   val summary: ChannelSummary? = null, val unreadCount: Int = 0) {
    companion object {
        fun parse(j: JSONObject) = Channel(j.str("code"), j.str("name"), j.str("avatarData"),
            j.optJSONObject("permissions")?.optBoolean("canMentionAll") == true,
            j.optJSONObject("permissions")?.optBoolean("canAnnounce") == true,
            j.optJSONObject("permissions")?.optBoolean("canManageAvatar") == true,
            j.optJSONObject("summary")?.let { ChannelSummary(it.str("messageId"), it.str("nick"), it.str("text"), it.str("kind"), it.str("at")) },
            j.optInt("unreadCount").coerceAtLeast(0))
    }
}

data class ChannelSummary(val messageId: String, val nick: String, val text: String, val kind: String, val at: String) {
    val preview: String get() {
        val body = when (kind) { "image" -> "[图片]"; "video" -> "[视频]"; else -> text.replace(Regex("\\s+"), " ").trim() }
        return if (nick.isBlank()) body else "$nick:$body"
    }
}
data class Message(val id: String, val nick: String, val text: String, val avatar: String, val building: String, val role: String, val mentionRef: String, val mine: Boolean, val pinned: Boolean, val mentioned: Boolean, val reply: String, val mediaId: String, val mediaKind: String,
                   val canDelete: Boolean = false, val canPin: Boolean = false, val canMute: Boolean = false, val announcement: Boolean = false) {
    companion object {
        fun parse(j: JSONObject) = Message(
            j.str("id"), j.str("nick"), j.str("text"), j.str("avatarUrl"),
            j.str("authorBuildingName"), j.optJSONObject("identity")?.str("label").orEmpty(),
            j.str("mentionRef"), j.optBoolean("mine"), j.optBoolean("pinned"),
            j.optBoolean("mentionedMe"), j.optJSONObject("reply")?.let { r ->
                r.str("nick") + ": " + r.str("text")
            }.orEmpty(), j.optJSONObject("media")?.str("id").orEmpty(),
            j.optJSONObject("media")?.str("kind").orEmpty(),
            j.optJSONObject("permissions")?.optBoolean("canDelete") == true,
            j.optJSONObject("permissions")?.optBoolean("canPin") == true,
            j.optJSONObject("permissions")?.optBoolean("canMute") == true,
            j.str("type") == "announcement"
        )
    }
}
data class AppState(
    val endpoint: String = "", val building: String = "", val buildings: List<Building> = emptyList(),
    val machines: List<Machine> = emptyList(), val tasks: List<LaundryTask> = emptyList(),
    val profile: Profile? = null, val favorites: Set<String> = emptySet(),
    val channels: List<Channel> = emptyList(), val channel: String = "public", val messages: List<Message> = emptyList(),
    val discussionMachine: String = "",
    val messageNotices: List<JSONObject> = emptyList(), val messagesPage: Int = 1, val messagesHasMore: Boolean = false,
    val queueNotices: List<JSONObject> = emptyList(),
    val channelConnected: Boolean = false,
    val pendingShare: PendingShare? = null,
    val queues: List<JSONObject> = emptyList(), val social: JSONObject = JSONObject(), val access: JSONObject = JSONObject(),
    val announcements: List<JSONObject> = emptyList(), val loading: Boolean = false, val busy: Boolean = false,
    val error: String? = null, val info: String? = null, val updatedAt: Long? = null, val boardWarning: String? = null,
    val tab: Int = 0, val appearance: String = "system", val palette: String = "blue", val quality: String = "AUTO",
    val announcementsOn: Boolean = true, val telemetryOn: Boolean = false, val notificationsOn: Boolean = false,
    val favoriteRemindersOn: Boolean = false, val reminderStart: String = "19:00", val reminderEnd: String = "22:00",
    val authReady: Boolean = true, val sessionRestoring: Boolean = false, val authError: String? = null,
    val boardFromCache: Boolean = false, val assistant: AssistantSnapshot = AssistantSnapshot(),
    /** Local receipt time of a successful authenticated task refresh, separate from the public board. */
    val tasksUpdatedAt: Long? = null, val tasksRefreshFailed: Boolean = false
)
