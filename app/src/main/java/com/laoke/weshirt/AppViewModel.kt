package com.laoke.weshirt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoke.weshirt.data.*
import com.laoke.weshirt.platform.PlatformCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("preferences", 0)
    val session = SecureSession(app)
    val api = ApiClient(session, app.cacheDir)
    private val refreshMutex = Mutex()
    private var refreshPending = false
    private val mutationMutex = Mutex()
    private var generation = 0
    private val telemetryBuffer = ArrayDeque<JSONObject>()
    private var telemetrySentAt = 0L
    private val _state = MutableStateFlow(AppState(endpoint = prefs.getString("endpoint", BuildConfig.API_BASE_URL).orEmpty(), building = prefs.getString("building", "").orEmpty(), appearance = prefs.getString("appearance", "system").orEmpty(), palette = prefs.getString("palette", "blue").orEmpty(), quality = prefs.getString("quality", "AUTO").orEmpty(), telemetryOn = prefs.getBoolean("telemetry", false)))
    val state = _state.asStateFlow()
    private val platform: PlatformCoordinator = PlatformCoordinator(app, session, api) { refresh() }
    init {
        api.baseUrl = _state.value.endpoint
        api.onMetric = { path, duration, status -> viewModelScope.launch {
            if(_state.value.telemetryOn) {
                val safePath = when { path.matches(Regex("/api/tasks/[^/]+/take")) -> "/api/tasks/:id/take"; path.matches(Regex("/api/tasks/[^/]+")) -> "/api/tasks/:id"; path in setOf("/api/machines","/api/tasks","/api/auth/me","/api/messages","/api/queue","/api/occupancy","/api/user/profile","/api/user/notify") -> path; else -> "/api/other" }
                if(telemetryBuffer.size < 20) telemetryBuffer.add(JSONObject().put("name","api_request").put("route",route()).put("endpoint",safePath).put("durationMs",duration).put("status",status))
            }
        } }
        api.onSessionInvalidated = {
            generation++
            platform.clearLocal()
            coil.Coil.imageLoader(app).memoryCache?.clear()
            _state.update { it.copy(profile = null, tasks = emptyList(), messages = emptyList(), social = JSONObject(), favorites = emptySet(), queues = emptyList(), notificationsOn = false) }
        }
        platform.initialize()
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        refresh()
    }
    fun platformSessionChanged() { platform.sessionChanged(); refresh() }
    fun notificationPermissionDenied() { platform.enableNotifications(false); _state.update { it.copy(notificationsOn = false) }; message("系统通知已关闭，你仍可在应用内查看洗衣状态") }
    fun message(text: String) { _state.update { it.copy(info = text) } }
    fun clearFeedback() { _state.update { it.copy(info = null, error = null) } }
    fun tab(value: Int) { _state.update { it.copy(tab = value) }; if(_state.value.telemetryOn && telemetryBuffer.size < 20) telemetryBuffer.add(JSONObject().put("name","page_view").put("route",route())); refresh() }
    private fun route() = listOf("/","/task","/board","/settings")[_state.value.tab.coerceIn(0,3)]
    private fun flushTelemetry() {
        if(!_state.value.telemetryOn || telemetryBuffer.isEmpty() || System.currentTimeMillis()-telemetrySentAt < 60_000) return
        val batch = telemetryBuffer.toList(); telemetryBuffer.clear(); telemetrySentAt=System.currentTimeMillis()
        viewModelScope.launch { runCatching { api.call("/api/telemetry","POST",JSONObject().put("events",JSONArray(batch)),auth=false) } }
    }
    fun preference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        _state.update { when(key) { "appearance" -> it.copy(appearance = value); "palette" -> it.copy(palette = value); "quality" -> it.copy(quality = value); else -> it } }
    }
    fun telemetry(value: Boolean) { prefs.edit().putBoolean("telemetry", value).apply(); _state.update { it.copy(telemetryOn = value) }; if(!value) telemetryBuffer.clear() }
    fun configure(endpoint: String) {
        val url = api.validEndpoint(endpoint) ?: return message("请输入完整的 HTTPS 服务地址，不含路径、账号或参数")
        mutate(null) {
            if (url != api.baseUrl) {
                generation++
                telemetryBuffer.clear()
                com.laoke.weshirt.platform.login.DingTalkLoginBridge.clear(getApplication())
                getApplication<Application>().getSharedPreferences("native_login_origin", 0).edit().clear().commit()
                platform.beforeSessionCleared()
                session.clear()
                _state.update { AppState(endpoint = url, appearance = it.appearance, palette = it.palette, notificationsOn = false) }
            }
            api.baseUrl = url; prefs.edit().putString("endpoint", url).apply()
        }
    }
    fun selectBuilding(code: String) {
        if (_state.value.profile == null) { generation++; prefs.edit().putString("building", code).apply(); _state.update { it.copy(building = code, machines = emptyList()) }; refresh() }
        else mutate("住宿楼已更新") { api.call("/api/user/profile", "POST", JSONObject().put("dormBuilding", code)); generation++; prefs.edit().putString("building", code).apply(); _state.update { it.copy(building = code, machines = emptyList()) } }
    }
    fun refresh(): kotlinx.coroutines.Job = viewModelScope.launch {
        if (api.baseUrl.isBlank()) return@launch
        if (!refreshMutex.tryLock()) { refreshPending = true; return@launch }
        val version = generation
        _state.update { it.copy(loading = true) }
        try {
            val site = api.call("/api/site", auth = false)
            if(version != generation) return@launch
            val buildings = site.optJSONArray("buildings")?.objects().orEmpty().filter { it.optBoolean("enabled", true) }.map { Building(it.str("code"), it.str("name")) }
            val signedIn = session.read() != null
            val profile = if(signedIn) Profile.parse(api.call("/api/auth/me").optJSONObject("user") ?: JSONObject()) else null
            val building = profile?.building?.takeIf { b -> buildings.any { it.code == b } } ?: _state.value.building.takeIf { b -> buildings.any { it.code == b } }.orEmpty()
            val result = coroutineScope {
                val board = async { if (building.isNotBlank()) api.call("/api/machines?dorm=${api.encoded(building)}", auth = false) else JSONObject() }
                val tasks = async { if (signedIn) api.call("/api/tasks") else JSONObject() }
                val favorites = async { if (signedIn) api.call("/api/user/laundry-preferences").optJSONObject("preferences") ?: JSONObject() else JSONObject() }
                Triple(board.await(), tasks.await(), favorites.await())
            }
            if(version != generation) return@launch
            val occupancy = if(building.isNotBlank()) api.call("/api/occupancy?dorm=${api.encoded(building)}", auth = false).optJSONObject("occupancy") else null
            val machines = result.first.optJSONObject("board")?.optJSONArray("floors")?.objects().orEmpty().flatMap { it.optJSONArray("machines")?.objects().orEmpty() }.map(Machine::parse).map { machine ->
                val o = occupancy?.optJSONObject(machine.id); val care = o?.optJSONObject("care")
                machine.copy(note = care?.str("note") ?: machine.note, disinfectant = care?.optBoolean("useDisinfectant") ?: machine.disinfectant, selfCleaned = care?.optBoolean("selfCleaned") ?: machine.selfCleaned, occupantId = o?.str("userId").orEmpty(), suspectedUncollected = o?.optBoolean("suspectedUncollected") == true)
            }
            val tasks = result.second.optJSONArray("tasks")?.objects().orEmpty().map(LaundryTask::parse)
            val queues = if(signedIn && building.isNotBlank()) api.call("/api/queue?dorm=${api.encoded(building)}").optJSONArray("queue")?.objects().orEmpty() else emptyList()
            val announcementPrefs = if(signedIn) api.call("/api/user/announcements") else JSONObject().put("enabled", prefs.getBoolean("announcements", true))
            val favs = result.third.optJSONArray("favorites") ?: JSONArray()
            if(version != generation) return@launch
            _state.update { it.copy(buildings = buildings, building = building, profile = profile, machines = machines, tasks = tasks, queues = queues, announcementsOn = announcementPrefs.optBoolean("enabled", true), favorites = (0 until favs.length()).map { favs.optString(it) }.toSet(), updatedAt = epoch(result.first.optJSONObject("board")?.str("updatedAt").orEmpty()), favoriteRemindersOn = result.third.optBoolean("enabled"), reminderStart = result.third.optJSONArray("timeWindows")?.optJSONObject(0)?.str("start", "19:00") ?: "19:00", reminderEnd = result.third.optJSONArray("timeWindows")?.optJSONObject(0)?.str("end", "22:00") ?: "22:00") }
            if(_state.value.tab == 2) loadChannelsNow(version)
            if(_state.value.tab == 1 && signedIn) loadSocialNow(version)
            if (version == generation) platform.synchronize(_state.value)
            if(building.isNotBlank() && _state.value.announcementsOn) {
                val announcements = api.call("/api/announcements?dorm=${api.encoded(building)}", auth = false).optJSONArray("announcements")?.objects().orEmpty().filter { announcementPrefs.optJSONObject("dismissed")?.str(it.str("id")) != it.str("version") && prefs.getString("dismiss.${profile?.id ?: "guest"}.${it.str("id")}", null) != it.str("version") && !seenAnnouncements.contains(it.str("id") + ":" + it.str("version")) }
                if(version == generation) _state.update { it.copy(announcements = announcements) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (version == generation) _state.update { it.copy(error = e.message ?: "加载失败") } }
        finally { _state.update { it.copy(loading = false) }; refreshMutex.unlock(); flushTelemetry(); if(refreshPending) { refreshPending = false; refresh() } }
    }
    private val seenAnnouncements = mutableSetOf<String>()
    fun dismissAnnouncement(permanent: Boolean) = mutate(null) {
        val a = _state.value.announcements.firstOrNull() ?: return@mutate
        if(permanent) {
            if(_state.value.profile != null) api.call("/api/user/announcements", "PATCH", JSONObject().put("dismiss", JSONObject().put("id", a.str("id")).put("version", a.get("version"))))
            prefs.edit().putString("dismiss.${_state.value.profile?.id ?: "guest"}.${a.str("id")}", a.str("version")).apply()
        }
        seenAnnouncements.add(a.str("id") + ":" + a.str("version")); _state.update { it.copy(announcements = it.announcements.drop(1)) }
    }
    fun mutate(success: String? = "已保存", wait: Boolean = false, block: suspend () -> Unit): kotlinx.coroutines.Job = viewModelScope.launch {
        if(wait) mutationMutex.lock() else if(!mutationMutex.tryLock()) return@launch
        _state.update { it.copy(busy = true) }
        try { block(); if(success != null) message(success); refresh() }
        catch(e: CancellationException) { throw e } catch(e: Exception) { _state.update { it.copy(error = e.message ?: "操作未完成") } }
        finally { _state.update { it.copy(busy = false) }; mutationMutex.unlock() }
    }
    fun register(machine: Machine, minutes: Int, note: String, disinfectant: Boolean, cleaned: Boolean, useMachine: Boolean, selfCleanProgram: Boolean) = mutate("登记成功") {
        api.call("/api/tasks", "POST", JSONObject().put("machineId", machine.id).put("minutes", minutes).put("mode", if(useMachine) "machine" else "duration").put("note", note).put("useDisinfectant", disinfectant).put("selfCleaned", cleaned).apply { if(selfCleanProgram) put("program", "self_clean") })
    }
    fun favorite(machine: Machine) = mutate(null) { api.call("/api/user/favorites/toggle", "POST", JSONObject().put("machineId", machine.id)) }
    fun queue(machine: Machine) = mutate("已加入队列；提醒以设备实际状态为准") { api.call("/api/queue", "POST", JSONObject().put("machineId", machine.id).put("dorm", _state.value.building).put("durationMin", if(machine.kind == "dryer") 60 else 30)) }
    fun take(task: LaundryTask) = mutate("已确认取衣") { api.call("/api/tasks/${api.encoded(task.id)}/take", "POST") }
    fun cancelQueue(machineId: String) = mutate("已取消排队") { api.call("/api/queue?machineId=${api.encoded(machineId)}", "DELETE") }
    fun nick(value: String) = mutate { api.call("/api/user/profile", "POST", JSONObject().put("nick", value)) }
    fun randomNick(callback: (String) -> Unit) = mutate(null) { val j=api.call("/api/user/random?field=nick"); callback(j.str("nick", j.str("value"))) }
    fun toggleAnnouncements(value: Boolean) = mutate { if(_state.value.profile != null) api.call("/api/user/announcements", "PATCH", JSONObject().put("enabled", value)); prefs.edit().putBoolean("announcements", value).apply(); _state.update { it.copy(announcementsOn = value) } }
    fun toggleNotify(value: Boolean) = mutate(null) {
        api.call("/api/user/notify", "POST", JSONObject().put("on", value))
        val status = platform.enableNotifications(value)
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        message(status)
    }
    fun saveReminders(enabled: Boolean, start: String, end: String) = mutate { api.call("/api/user/laundry-preferences", "PATCH", JSONObject().put("enabled", enabled).put("favorites", JSONArray(_state.value.favorites.toList())).put("timeWindows", JSONArray().put(JSONObject().put("start", start).put("end", end).put("days", JSONArray(listOf(1,2,3,4,5,6,7)))))) }
    fun channel(code: String) { _state.update { it.copy(channel = code, messages = emptyList()) }; viewModelScope.launch { try { loadMessagesNow(generation) } catch(e: Exception) { message(e.message.orEmpty()) } } }
    private suspend fun loadChannelsNow(version: Int) {
        val j = api.call("/api/messages/channels")
        val channels = j.optJSONArray("channels")?.objects().orEmpty().map { Channel(it.str("code"), it.str("name"), it.str("avatarData"), it.optJSONObject("permissions")?.optBoolean("canMentionAll") == true) }
        if(version != generation) return
        _state.update { it.copy(channels = channels, channel = it.channel.takeIf { c -> channels.any { ch -> ch.code == c } } ?: "public") }
        loadMessagesNow(version)
    }
    private suspend fun loadMessagesNow(version: Int) {
        val channel = _state.value.channel
        val j = api.call("/api/messages?channel=${api.encoded(channel)}&limit=50")
        val rows = j.optJSONArray("messages")?.objects().orEmpty().map { Message(it.str("id"), it.str("nick"), it.str("text"), it.str("avatarUrl"), it.str("authorBuildingName"), it.optJSONObject("identity")?.str("label").orEmpty(), it.str("mentionRef"), it.optBoolean("mine"), it.optBoolean("pinned"), it.optBoolean("mentionedMe"), it.optJSONObject("reply")?.let { r -> r.str("nick") + ": " + r.str("text") }.orEmpty(), it.optJSONObject("media")?.str("id").orEmpty(), it.optJSONObject("media")?.str("kind").orEmpty()) }
        if(version == generation && channel == _state.value.channel) _state.update { it.copy(messages = rows) }
    }
    fun sendMessage(text: String, reply: String?, mentions: List<String>, all: Boolean) = mutate(null) {
        api.call("/api/messages", "POST", JSONObject().put("channel", _state.value.channel).put("text", text).put("replyTo", reply).put("mentions", JSONArray(mentions)).put("mentionAll", all)); loadMessagesNow(generation)
    }
    fun report(message: Message) = mutate("举报已提交管理员") { api.call("/api/messages/${api.encoded(message.id)}/report", "POST", JSONObject().put("reason", "other").put("detail", "用户通过原生客户端举报")) }
    private suspend fun loadSocialNow(version: Int) {
        val social = api.call("/api/social"); val pickups = api.call("/api/social/pickups"); social.put("receivedPickups", pickups.optJSONArray("received")); social.put("sentPickups", pickups.optJSONArray("sent")); val queue = api.call("/api/queue?dorm=${api.encoded(_state.value.building)}")
        if(version == generation) _state.update { it.copy(social = social, queues = queue.optJSONArray("queue")?.objects().orEmpty()) }
    }
    fun pickup(friend: String, task: String) = mutate("代取请求已发送") { api.call("/api/social/pickup", "POST", JSONObject().put("friendId", friend).put("taskId", task)) }
    fun respondPickup(id: String, action: String) = mutate { api.call("/api/social/pickups/${api.encoded(id)}/respond", "POST", JSONObject().put("action", action)) }
    fun invite(callback: (String) -> Unit) = mutate(null) { val j = api.call("/api/social/invite", "POST", JSONObject().put("kind", "friend")); callback(j.str("url", j.str("link", j.str("ticket")))) }
    fun acceptInvite(ticket: String) = mutate("朋友已绑定") { api.call("/api/social/accept", "POST", JSONObject().put("ticket", ticket)) }
    fun assistant(text: String, callback: (String) -> Unit) = mutate(null) { val j = api.call("/api/assistant/chat", "POST", JSONObject().put("input", text)); callback(j.str("reply", j.str("message"))) }
    fun logout() = mutate("已退出登录") {
        generation++
        com.laoke.weshirt.platform.login.DingTalkLoginBridge.clear(getApplication())
        try {
            platform.beforeSessionCleared()
            api.logout()
        } finally {
            session.clear(); platform.clearLocal()
            _state.update { it.copy(profile = null, tasks = emptyList(), social = JSONObject(), messages = emptyList(), favorites = emptySet(), notificationsOn = false) }
        }
    }
}
