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
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    private val notificationIntent = java.util.concurrent.atomic.AtomicLong(0)
    private val announcementIntent = java.util.concurrent.atomic.AtomicLong(0)
    private data class CachedMetadata(val value: JSONObject, val generation: Int, val until: Long)
    private val metadata = mutableMapOf<String, CachedMetadata>()
    private suspend fun metadata(path: String, auth: Boolean = true): JSONObject {
        val version = generation.get()
        val now = android.os.SystemClock.elapsedRealtime()
        metadata[path]?.takeIf { it.generation == version && now < it.until }?.let { return it.value }
        val value = api.call(path, auth = auth)
        if(version == generation.get()) metadata[path] = CachedMetadata(value, version, now + 300_000)
        return value
    }
    private val telemetryBuffer = ArrayDeque<JSONObject>()
    private var telemetrySentAt = 0L
    private val _state = MutableStateFlow(AppState(endpoint = prefs.getString("endpoint", BuildConfig.API_BASE_URL).orEmpty(), building = prefs.getString("building", "").orEmpty(), appearance = prefs.getString("appearance", "system").orEmpty(), palette = prefs.getString("palette", "blue").orEmpty(), quality = prefs.getString("quality", "AUTO").orEmpty(), telemetryOn = prefs.getBoolean("telemetry", false), announcementsOn = prefs.getBoolean("announcements", true)))
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
            generation.incrementAndGet()
            platform.clearLocal()
            coil.Coil.imageLoader(app).memoryCache?.clear()
            _state.update { it.copy(profile = null, tasks = emptyList(), messages = emptyList(), messageNotices = emptyList(), queueNotices = emptyList(), channelConnected = false, social = JSONObject(), access = JSONObject(), favorites = emptySet(), queues = emptyList(), notificationsOn = false) }
        }
        platform.initialize()
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        refresh()
    }
    fun platformSessionChanged() { generation.incrementAndGet(); metadata.clear(); platform.sessionChanged(); refresh() }
    fun notificationPermissionDenied() { notificationIntent.incrementAndGet(); platform.enableNotifications(false); _state.update { it.copy(notificationsOn = false) }; message("系统通知已关闭，你仍可在应用内查看洗衣状态") }
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
                generation.incrementAndGet()
                telemetryBuffer.clear()
                com.laoke.weshirt.platform.login.DingTalkLoginBridge.clear(getApplication())
                getApplication<Application>().getSharedPreferences("native_login_origin", 0).edit().clear().commit()
                platform.beforeSessionCleared()
                session.clear()
                _state.update { AppState(endpoint = url, appearance = it.appearance, palette = it.palette, notificationsOn = false, announcementsOn = prefs.getBoolean("announcements", true)) }
            }
            api.baseUrl = url; prefs.edit().putString("endpoint", url).apply()
        }
    }
    fun selectBuilding(code: String) {
        if (_state.value.profile == null) { generation.incrementAndGet(); prefs.edit().putString("building", code).apply(); _state.update { it.copy(building = code, machines = emptyList()) }; refresh() }
        else mutate("住宿楼已更新") { api.call("/api/user/profile", "POST", JSONObject().put("dormBuilding", code)); generation.incrementAndGet(); prefs.edit().putString("building", code).apply(); _state.update { it.copy(building = code, machines = emptyList()) } }
    }
    fun refresh(forceMetadata: Boolean = false): kotlinx.coroutines.Job = viewModelScope.launch {
        if (api.baseUrl.isBlank()) return@launch
        if(forceMetadata) metadata.clear()
        if (!refreshMutex.tryLock()) { refreshPending = true; return@launch }
        val version = generation.get()
        _state.update { it.copy(loading = true) }
        try {
            val site = metadata("/api/site", auth = false)
            if(version != generation.get()) return@launch
            val buildings = site.optJSONArray("buildings")?.objects().orEmpty().filter { it.optBoolean("enabled", true) }.map { Building(it.str("code"), it.str("name")) }
            val signedIn = session.read() != null
            val profile = if(signedIn) Profile.parse(metadata("/api/auth/me").optJSONObject("user") ?: JSONObject()) else null
            // Permission revocation takes effect on the next refresh; never reuse metadata cache here.
            val access = if(signedIn) api.call("/api/user/permissions") else JSONObject()
            val building = profile?.building?.takeIf { b -> buildings.any { it.code == b } } ?: _state.value.building.takeIf { b -> buildings.any { it.code == b } }.orEmpty()
            val result = coroutineScope {
                val board = async { if (building.isNotBlank()) api.call("/api/machines?dorm=${api.encoded(building)}", auth = false) else JSONObject() }
                val tasks = async { if (signedIn) api.call("/api/tasks") else JSONObject() }
                val favorites = async { if (signedIn) metadata("/api/user/laundry-preferences").optJSONObject("preferences") ?: JSONObject() else JSONObject() }
                Triple(board.await(), tasks.await(), favorites.await())
            }
            if(version != generation.get()) return@launch
            val occupancy = if(building.isNotBlank()) api.call("/api/occupancy?dorm=${api.encoded(building)}", auth = false).optJSONObject("occupancy") else null
            val machines = result.first.optJSONObject("board")?.optJSONArray("floors")?.objects().orEmpty().flatMap { floor ->
                floor.optJSONArray("machines")?.objects().orEmpty().map { value ->
                    Machine.parse(value).let { machine -> machine.copy(floorCode = machine.floorCode.ifBlank { floor.str("code") }, floorName = machine.floorName.ifBlank { floor.str("name", floor.str("code")) }) }
                }
            }.map { machine ->
                val o = occupancy?.optJSONObject(machine.id); val care = o?.optJSONObject("care")
                machine.copy(note = care?.str("note") ?: machine.note, disinfectant = care?.optBoolean("useDisinfectant") ?: machine.disinfectant, selfCleaned = care?.optBoolean("selfCleaned") ?: machine.selfCleaned, occupantId = o?.str("userId").orEmpty(), suspectedUncollected = machine.suspectedUncollected || o?.optBoolean("suspectedUncollected") == true)
            }
            val tasks = result.second.optJSONArray("tasks")?.objects().orEmpty().map(LaundryTask::parse)
            val queues = if(signedIn && building.isNotBlank()) api.call("/api/queue?dorm=${api.encoded(building)}").optJSONArray("queue")?.objects().orEmpty() else emptyList()
            val announcementPrefs = if(signedIn) metadata("/api/user/announcements") else JSONObject().put("enabled", prefs.getBoolean("announcements", true))
            val favs = result.third.optJSONArray("favorites") ?: JSONArray()
            if(version != generation.get()) return@launch
            _state.update { it.copy(buildings = buildings, building = building, profile = profile, access = access, machines = machines, tasks = tasks, queues = queues, announcementsOn = announcementPrefs.optBoolean("enabled", true) && prefs.getBoolean("announcements", true), announcements = if (announcementPrefs.optBoolean("enabled", true) && prefs.getBoolean("announcements", true)) it.announcements else emptyList(), favorites = (0 until favs.length()).map { favs.optString(it) }.toSet(), updatedAt = epoch(result.first.optJSONObject("board")?.str("updatedAt").orEmpty()), favoriteRemindersOn = result.third.optBoolean("enabled"), reminderStart = result.third.optJSONArray("timeWindows")?.optJSONObject(0)?.str("start", "19:00") ?: "19:00", reminderEnd = result.third.optJSONArray("timeWindows")?.optJSONObject(0)?.str("end", "22:00") ?: "22:00") }
            if(signedIn) loadNoticesNow(version)
            if(_state.value.tab == 2 && signedIn) loadChannelsNow(version)
            if(_state.value.tab == 1 && signedIn) loadSocialNow(version)
            if (version == generation.get()) platform.synchronize(_state.value)
            if(building.isNotBlank() && _state.value.announcementsOn) {
                val announcements = metadata("/api/announcements?dorm=${api.encoded(building)}", auth = false).optJSONArray("announcements")?.objects().orEmpty().filter { announcementPrefs.optJSONObject("dismissed")?.str(it.str("id")) != it.str("version") && prefs.getString("dismiss.${profile?.id ?: "guest"}.${it.str("id")}", null) != it.str("version") && !seenAnnouncements.contains(it.str("id") + ":" + it.str("version")) }
                if(version == generation.get()) _state.update { it.copy(announcements = if (it.announcementsOn && prefs.getBoolean("announcements", true)) announcements else emptyList()) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (version == generation.get()) _state.update { it.copy(error = e.message ?: "加载失败") } }
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
        try { block(); metadata.clear(); if(success != null) message(success); refresh() }
        catch(e: CancellationException) { throw e } catch(e: Exception) { _state.update { it.copy(error = e.message ?: "操作未完成") } }
        finally { _state.update { it.copy(busy = false) }; mutationMutex.unlock() }
    }
    fun register(machine: Machine, minutes: Int, note: String, disinfectant: Boolean, cleaned: Boolean,
                 useMachine: Boolean, selfCleanProgram: Boolean, onSuccess: () -> Unit = {}, onFailure: (String) -> Unit = {}) = mutate("登记成功") {
        try {
            api.call("/api/tasks", "POST", TaskRegistration.body(machine, minutes, note, disinfectant, cleaned, useMachine, selfCleanProgram))
            onSuccess()
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: Exception) { onFailure(problem.message ?: "登记未完成，请重试"); throw problem }
    }
    fun favorite(machine: Machine) = mutate(null) { api.call("/api/user/favorites/toggle", "POST", JSONObject().put("machineId", machine.id)) }
    fun queue(machine: Machine) = mutate("已加入队列；提醒以设备实际状态为准") { api.call("/api/queue", "POST", JSONObject().put("machineId", machine.id).put("dorm", _state.value.building).put("durationMin", if(machine.kind == "dryer") 60 else 30)) }
    fun take(task: LaundryTask) = mutate("已确认取衣") { api.call("/api/tasks/${api.encoded(task.id)}/take", "POST") }
    fun cancelQueue(machineId: String) = mutate("已取消排队") { api.call("/api/queue?machineId=${api.encoded(machineId)}", "DELETE") }
    fun nick(value: String) = mutate { api.call("/api/user/profile", "POST", JSONObject().put("nick", value)) }
    fun randomNick(callback: (String) -> Unit) = mutate(null) { val j=api.call("/api/user/random?field=nick"); callback(j.str("nick", j.str("value"))) }
    fun toggleAnnouncements(value: Boolean): kotlinx.coroutines.Job {
        val intent = announcementIntent.incrementAndGet()
        val account = generation.get()
        // Revoking a local preference must not wait for an unrelated write or the network.
        if (!value) {
            prefs.edit().putBoolean("announcements", false).commit()
            _state.update { it.copy(announcementsOn = false, announcements = emptyList()) }
            message("开屏公告已关闭")
        }
        return mutate(null, wait = true) {
            if (intent != announcementIntent.get() || account != generation.get()) return@mutate
            if (_state.value.profile != null) api.call("/api/user/announcements", "PATCH", JSONObject().put("enabled", value))
            if (value && intent == announcementIntent.get() && account == generation.get()) {
                prefs.edit().putBoolean("announcements", true).commit()
                _state.update { it.copy(announcementsOn = true) }
                message("开屏公告已开启")
            }
        }
    }
    fun toggleNotify(value: Boolean): kotlinx.coroutines.Job {
        val intent = notificationIntent.incrementAndGet()
        val account = generation.get()
        if (!value) {
            platform.enableNotifications(false)
            _state.update { it.copy(notificationsOn = false) }
            message("通知已关闭")
        }
        return mutate(null, wait = true) {
            if (intent != notificationIntent.get() || account != generation.get()) return@mutate
            api.call("/api/user/notify", "POST", JSONObject().put("on", value))
            if (value && intent == notificationIntent.get() && account == generation.get()) {
                val status = platform.enableNotifications(true)
                _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
                message(status)
            }
        }
    }
    fun saveReminders(enabled: Boolean, start: String, end: String) = mutate { api.call("/api/user/laundry-preferences", "PATCH", JSONObject().put("enabled", enabled).put("favorites", JSONArray(_state.value.favorites.toList())).put("timeWindows", JSONArray().put(JSONObject().put("start", start).put("end", end).put("days", JSONArray(listOf(1,2,3,4,5,6,7)))))) }
    fun channel(code: String, machineId: String? = null) { _state.update { it.copy(channel = code, discussionMachine = machineId.orEmpty(), messages = emptyList(), messagesPage = 1, messagesHasMore = false) }; refreshMessages() }
    private val messagesLock = Mutex()
    private var messagesPending = false
    fun channelConnection(connected: Boolean) { _state.update { it.copy(channelConnected = connected) } }
    fun receiveShare(raw: String) {
        val share = parseIncomingShare(raw, _state.value.endpoint)
        if (share == null) message("邀请链接无效或来自其他服务，请在对应服务中打开")
        else _state.update { it.copy(pendingShare = share) }
    }
    fun dismissShare() { _state.update { it.copy(pendingShare = null) } }
    fun refreshMessages(): kotlinx.coroutines.Job = viewModelScope.launch {
        if (!messagesLock.tryLock()) { messagesPending = true; return@launch }
        try { val version = generation.get(); loadMessagesNow(version); loadNoticesNow(version) }
        catch(cancel: CancellationException) { throw cancel }
        catch(problem: Exception) { message(problem.message.orEmpty()) }
        finally { messagesLock.unlock(); if (messagesPending) { messagesPending = false; refreshMessages() } }
    }
    fun moreMessages() = viewModelScope.launch {
        if (!messagesLock.tryLock()) return@launch
        try { if (_state.value.messagesHasMore) loadMessagesNow(generation.get(), _state.value.messagesPage + 1) }
        catch(cancel: CancellationException) { throw cancel }
        catch(problem: Exception) { message(problem.message.orEmpty()) }
        finally { messagesLock.unlock(); if (messagesPending) { messagesPending = false; refreshMessages() } }
    }
    private suspend fun loadNoticesNow(version: Int) {
        val (notices, queueNotices) = coroutineScope {
            val messages = async { api.call("/api/messages/notices").optJSONArray("notices")?.objects().orEmpty() }
            val queue = async { api.call("/api/queue/notices").optJSONArray("notices")?.objects().orEmpty().take(30) }
            messages.await() to queue.await()
        }
        if (version == generation.get()) _state.update { it.copy(messageNotices = notices, queueNotices = queueNotices) }
    }
    fun readChannelNotices() = mutate(null) {
        val selected = _state.value.channel
        api.call("/api/messages/notices/read", "POST", JSONObject().put("channel", selected))
    }
    private suspend fun loadChannelsNow(version: Int) {
        val j = api.call("/api/messages/channels")
        val channels = j.optJSONArray("channels")?.objects().orEmpty().map { Channel(it.str("code"), it.str("name"), it.str("avatarData"), it.optJSONObject("permissions")?.optBoolean("canMentionAll") == true, it.optJSONObject("permissions")?.optBoolean("canAnnounce") == true, it.optJSONObject("permissions")?.optBoolean("canManageAvatar") == true) }
        if(version != generation.get()) return
        _state.update { it.copy(channels = channels, channel = it.channel.takeIf { c -> channels.any { ch -> ch.code == c } } ?: "public") }
        loadMessagesNow(version)
    }
    private suspend fun loadMessagesNow(version: Int, page: Int = 1) {
        val channel = _state.value.channel
        val machineId = _state.value.discussionMachine
        val filter = if (machineId.isNotBlank()) "&machineId=${api.encoded(machineId)}" else ""
        val j = api.call("/api/messages?channel=${api.encoded(channel)}&limit=50&page=$page$filter")
        val rows = j.optJSONArray("messages")?.objects().orEmpty().map(Message::parse)
        if(version == generation.get() && channel == _state.value.channel && machineId == _state.value.discussionMachine) _state.update { it.copy(messages = (if(page == 1) rows else it.messages + rows).distinctBy { m -> m.id }, messagesPage = page, messagesHasMore = j.optJSONObject("pagination")?.optBoolean("hasMore") == true) }
    }
    fun sendMessage(text: String, reply: String?, mentions: List<String>, all: Boolean) = mutate(null) {
        api.call("/api/messages", "POST", JSONObject().put("channel", _state.value.channel).put("text", text).put("replyTo", reply).put("mentions", JSONArray(mentions)).put("mentionAll", all)); loadMessagesNow(generation.get())
    }
    fun report(message: Message) = mutate("举报已提交管理员") { api.call("/api/messages/${api.encoded(message.id)}/report", "POST", JSONObject().put("reason", "other").put("detail", "用户通过原生客户端举报")) }
    private suspend fun loadSocialNow(version: Int) {
        val social = api.call("/api/social"); val pickups = api.call("/api/social/pickups"); social.put("receivedPickups", pickups.optJSONArray("received")); social.put("sentPickups", pickups.optJSONArray("sent")); val queue = api.call("/api/queue?dorm=${api.encoded(_state.value.building)}")
        if(version == generation.get()) _state.update { it.copy(social = social, queues = queue.optJSONArray("queue")?.objects().orEmpty()) }
    }
    fun pickup(friend: String, task: String) = mutate("代取请求已发送") { api.call("/api/social/pickup", "POST", JSONObject().put("friendId", friend).put("taskId", task)) }
    fun respondPickup(id: String, action: String) = mutate { api.call("/api/social/pickups/${api.encoded(id)}/respond", "POST", JSONObject().put("action", action)) }
    fun invite(callback: (String) -> Unit) = mutate(null) { val j = api.call("/api/social/invite", "POST", JSONObject().put("kind", "friend")); callback(j.str("url", j.str("link", j.str("ticket")))) }
    fun acceptInvite(ticket: String) = mutate("朋友已绑定") { api.call("/api/social/accept", "POST", JSONObject().put("ticket", ticket)) }
    fun assistant(text: String, callback: (String) -> Unit) = mutate(null) { val j = api.call("/api/assistant/chat", "POST", JSONObject().put("input", text)); callback(j.str("reply", j.str("message"))) }
    fun logout() = mutate("已退出登录") {
        generation.incrementAndGet()
        com.laoke.weshirt.platform.login.DingTalkLoginBridge.clear(getApplication())
        try {
            platform.beforeSessionCleared()
            api.logout()
        } finally {
            session.clear(); platform.clearLocal()
            _state.update { it.copy(profile = null, tasks = emptyList(), social = JSONObject(), access = JSONObject(), messages = emptyList(), messageNotices = emptyList(), queueNotices = emptyList(), channelConnected = false, favorites = emptySet(), notificationsOn = false) }
        }
    }
}
