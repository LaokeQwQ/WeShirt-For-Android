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
import okhttp3.OkHttpClient
import java.util.UUID

class AppViewModel @JvmOverloads constructor(app: Application, client: OkHttpClient? = null) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("preferences", 0)
    val session = SecureSession(app)
    val api = ApiClient(session, app.cacheDir, client)
    private val channelInbox = ChannelInbox(api)
    private var channelsJob: kotlinx.coroutines.Job? = null
    private val refreshMutex = Mutex()
    private var refreshPending = false
    private val mutationMutex = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    private val notificationIntent = java.util.concurrent.atomic.AtomicLong(0)
    private val announcementIntent = java.util.concurrent.atomic.AtomicLong(0)
    private data class CachedMetadata(val value: JSONObject, val generation: Int, val until: Long)
    private val metadata = mutableMapOf<String, CachedMetadata>()
    private suspend fun metadata(path: String, auth: Boolean = true, ttlMs: Long = 300_000): JSONObject {
        val version = generation.get()
        val now = android.os.SystemClock.elapsedRealtime()
        metadata[path]?.takeIf { it.generation == version && now < it.until }?.let { return it.value }
        val value = api.call(path, auth = auth)
        if(version == generation.get()) metadata[path] = CachedMetadata(value, version, now + ttlMs)
        return value
    }
    private val telemetryBuffer = ArrayDeque<JSONObject>()
    private var telemetrySentAt = 0L
    private val _state = MutableStateFlow(AppState(endpoint = serviceEndpoint(BuildConfig.DEBUG, BuildConfig.API_BASE_URL, prefs.getString("endpoint", null)), building = prefs.getString("building", "").orEmpty(), appearance = prefs.getString("appearance", "system").orEmpty(), palette = prefs.getString("palette", "blue").orEmpty(), quality = prefs.getString("quality", "AUTO").orEmpty(), telemetryOn = prefs.getBoolean("telemetry", false), announcementsOn = prefs.getBoolean("announcements", true)))
    val state = _state.asStateFlow()
    private val platform: PlatformCoordinator = PlatformCoordinator(app, session, api) { refresh() }
    init {
        if (!BuildConfig.DEBUG) prefs.edit().remove("endpoint").apply()
        api.baseUrl = _state.value.endpoint
        if (session.read()?.str("origin")?.let { it != api.baseUrl } == true) session.clear()
        val restoring = session.read() != null
        _state.update { it.copy(profile = api.cachedProfile(), authReady = !restoring, sessionRestoring = restoring) }
        api.onMetric = { path, duration, status -> viewModelScope.launch {
            if(_state.value.telemetryOn) {
                val safePath = when { path.matches(Regex("/api/tasks/[^/]+/take")) -> "/api/tasks/:id/take"; path.matches(Regex("/api/tasks/[^/]+")) -> "/api/tasks/:id"; path in setOf("/api/machines","/api/tasks","/api/auth/me","/api/messages","/api/queue","/api/occupancy","/api/user/profile","/api/user/notify") -> path; else -> "/api/other" }
                if(telemetryBuffer.size < 20) telemetryBuffer.add(JSONObject().put("name","api_request").put("route",route()).put("endpoint",safePath).put("durationMs",duration).put("status",status))
            }
        } }
        api.onSessionInvalidated = {
            generation.incrementAndGet()
            channelInbox.clear(); metadata.clear(); channelsJob?.cancel()
            platform.clearLocal()
            coil.Coil.imageLoader(app).memoryCache?.clear()
            _state.update { it.copy(profile = null, tasks = emptyList(), channels = emptyList(), messages = emptyList(), messageNotices = emptyList(), queueNotices = emptyList(), channelConnected = false, social = JSONObject(), access = JSONObject(), favorites = emptySet(), queues = emptyList(), notificationsOn = platform.notificationsEnabled, authReady = true, sessionRestoring = false, authError = null) }
        }
        platform.initialize()
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        refresh()
    }
    fun platformSessionChanged() {
        generation.incrementAndGet(); metadata.clear(); channelInbox.clear(); channelsJob?.cancel(); platform.sessionChanged()
        val restoring = session.read() != null
        _state.update { it.copy(profile = api.cachedProfile(), channels = emptyList(), messages = emptyList(), messageNotices = emptyList(), channelConnected = false,
            tasks = emptyList(), social = JSONObject(), access = JSONObject(), favorites = emptySet(), queues = emptyList(),
            authReady = !restoring, sessionRestoring = restoring, authError = null) }
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        // Apply only an explicit, still-enabled guest request once. An older
        // device preference must not overwrite another account's server choice.
        if (prefs.getBoolean("pendingGuestNotifyEnable", false) && platform.notificationsEnabled) toggleNotify(true)
        refresh()
    }
    fun prepareNotificationPermission(): Long {
        val request = notificationIntent.incrementAndGet()
        if (session.read() == null) prefs.edit().putBoolean("pendingGuestNotifyEnable", true).commit()
        platform.enableNotifications(true)
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        return request
    }
    fun notificationPermissionResult(request: Long, granted: Boolean) {
        if (request != notificationIntent.get()) return
        if (granted) toggleNotify(true) else notificationPermissionDenied()
    }
    fun notificationPermissionDenied() { notificationIntent.incrementAndGet(); prefs.edit().remove("pendingGuestNotifyEnable").commit(); platform.enableNotifications(false); _state.update { it.copy(notificationsOn = false) }; message("系统通知已关闭，你仍可在应用内查看洗衣状态") }
    fun message(text: String) { _state.update { it.copy(info = text) } }
    fun clearFeedback() { _state.update { it.copy(info = null, error = null) } }
    fun tab(value: Int) {
        _state.update { it.copy(tab = value.takeIf { index -> index in 0..4 } ?: 0) }
        if (_state.value.telemetryOn && telemetryBuffer.size < 20) telemetryBuffer.add(JSONObject().put("name", "page_view").put("route", route()))
        // Opening a directory must not wait for the board, queue and notification requests.
        if (value == 2 && _state.value.profile != null) refreshChannels()
        else if (value != 4 || !_state.value.authReady) refresh()
    }
    private fun route() = nativeTabRoute(_state.value.tab)
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
        if (!BuildConfig.DEBUG) return message("服务由应用内置，无需设置地址")
        val url = api.validEndpoint(endpoint) ?: return message("请输入完整的 HTTPS 服务地址，不含路径、账号或参数")
        mutate(null) {
            if (url != api.baseUrl) {
                generation.incrementAndGet()
                telemetryBuffer.clear()
                com.laoke.weshirt.platform.login.DingTalkLoginBridge.clear(getApplication())
                getApplication<Application>().getSharedPreferences("native_login_origin", 0).edit().clear().commit()
                platform.beforeSessionCleared()
                session.clear()
                _state.update { AppState(endpoint = url, appearance = it.appearance, palette = it.palette, notificationsOn = platform.notificationsEnabled, announcementsOn = prefs.getBoolean("announcements", true)) }
            }
            api.baseUrl = url; prefs.edit().putString("endpoint", url).apply()
        }
    }
    fun selectBuilding(code: String) {
        if (_state.value.profile == null) { generation.incrementAndGet(); prefs.edit().putString("building", code).apply(); _state.update { it.copy(building = code, machines = emptyList()) }; refresh() }
        else mutate("住宿楼已更新") { api.call("/api/user/profile", "POST", JSONObject().put("dormBuilding", code)); generation.incrementAndGet(); prefs.edit().putString("building", code).apply(); _state.update { it.copy(building = code, machines = emptyList()) } }
    }
    fun refresh(forceMetadata: Boolean = false): kotlinx.coroutines.Job = viewModelScope.launch {
        if (api.baseUrl.isBlank()) { _state.update { it.copy(authReady = session.read() == null, sessionRestoring = false) }; return@launch }
        if(forceMetadata) { metadata.clear(); channelInbox.invalidate(); if (_state.value.tab == 2) refreshChannels() }
        if (!refreshMutex.tryLock()) { refreshPending = true; return@launch }
        val version = generation.get()
        val signedIn = session.read() != null
        var authResolved = !signedIn
        _state.update { it.copy(loading = true, sessionRestoring = signedIn && !it.authReady, authError = null) }
        try {
            // Resolve identity independently of the board and optional features.
            // A slow public query must not masquerade as a logged-out account.
            val profile = if (signedIn) {
                val user = api.call("/api/auth/me").optJSONObject("user")
                    ?: throw ApiException(502, "profile_missing", "client_" + UUID.randomUUID().toString().replace("-", ""), "账号资料暂未返回，请重试")
                if (user.str("id").isBlank()) throw ApiException(502, "profile_invalid", "client_" + UUID.randomUUID().toString().replace("-", ""), "账号资料暂不可用，请重试")
                if (version != generation.get()) return@launch
                api.cacheProfile(user)
                Profile.parse(user)
            } else null
            if (version != generation.get()) return@launch
            authResolved = true
            _state.update { it.copy(profile = profile, authReady = true, sessionRestoring = false, authError = null) }
            val site = metadata("/api/site", auth = false, ttlMs = 30_000)
            if(version != generation.get()) return@launch
            val buildings = site.optJSONArray("buildings")?.objects().orEmpty().filter { it.optBoolean("enabled", true) }.map { Building(it.str("code"), it.str("name")) }
            // Permission revocation takes effect on the next refresh; never reuse metadata cache here.
            val access = if(signedIn) api.call("/api/user/permissions") else JSONObject()
            val building = profile?.building?.takeIf { b -> buildings.any { it.code == b } } ?: _state.value.building.takeIf { b -> buildings.any { it.code == b } }.orEmpty()
            val incoming = if (building.isNotBlank()) parseBoardSnapshot(api.call("/api/machines?dorm=${api.encoded(building)}", auth = false), building) else BoardSnapshot(emptyList(), null)
            if(version != generation.get()) return@launch
            val before = _state.value
            val board = preferLatestBoard(incoming, building, before.building, before.machines, before.updatedAt)
            // Publish the board immediately. A queue, profile-preference or notification outage
            // must not leave a successful machine response invisible until every request succeeds.
            _state.update { it.copy(buildings = buildings, building = building, profile = profile, access = access, machines = board.machines, updatedAt = board.updatedAt, boardWarning = board.warning, error = null) }
            if (_state.value.tab == 2 && signedIn) refreshChannels()
            val problems = mutableListOf<Throwable>()
            fun <T> Result<T>.readOr(previous: T): T = getOrElse { problems += it; previous }
            val p = access.optJSONObject("permissions")
            val mayReadTasks = signedIn && p?.optJSONObject("tasks")?.optBoolean("view", true) != false
            val mayReadQueue = signedIn && building.isNotBlank() && p?.optJSONObject("queue")?.optBoolean("view", true) != false
            val extra = coroutineScope {
                val tasks = async { optionalRefresh { if (mayReadTasks) api.call("/api/tasks") else JSONObject().put("tasks", JSONArray()) } }
                val occupancy = async { optionalRefresh { if (building.isNotBlank()) api.call("/api/occupancy?dorm=${api.encoded(building)}", auth = false) else JSONObject() } }
                val queue = async { optionalRefresh { if (mayReadQueue) api.call("/api/queue?dorm=${api.encoded(building)}") else JSONObject().put("queue", JSONArray()) } }
                Triple(tasks.await(), occupancy.await(), queue.await())
            }
            if(version != generation.get()) return@launch
            val tasks = extra.first.map { response ->
                val offset = epoch(response.str("serverNow"))?.minus(System.currentTimeMillis()) ?: 0L
                response.optJSONArray("tasks")?.objects().orEmpty().map { LaundryTask.parse(it).copy(clockOffsetMs = offset) }
            }.readOr(if (mayReadTasks) _state.value.tasks else emptyList())
            val queues = extra.third.map { it.optJSONArray("queue")?.objects().orEmpty() }.readOr(if (mayReadQueue) _state.value.queues else emptyList())
            val occupancy = extra.second.getOrNull()?.optJSONObject("occupancy")
            extra.second.exceptionOrNull()?.let { problems += it }
            val machines = board.machines.map { machine ->
                if (occupancy == null) machine else {
                    val o = occupancy.optJSONObject(machine.id); val care = o?.optJSONObject("care")
                    machine.copy(note = care?.str("note") ?: machine.note, disinfectant = care?.optBoolean("useDisinfectant") ?: machine.disinfectant, selfCleaned = care?.optBoolean("selfCleaned") ?: machine.selfCleaned, occupantId = o?.str("userId").orEmpty(), suspectedUncollected = machine.suspectedUncollected || o?.optBoolean("suspectedUncollected") == true)
                }
            }
            if(version != generation.get()) return@launch
            _state.update { it.copy(machines = machines, tasks = tasks, queues = queues) }
            val laundryPrefs = optionalRefresh { if(signedIn) metadata("/api/user/laundry-preferences").optJSONObject("preferences") ?: JSONObject() else JSONObject() }.readOr(JSONObject().put("favorites", JSONArray(_state.value.favorites.toList())).put("enabled", _state.value.favoriteRemindersOn).put("timeWindows", JSONArray().put(JSONObject().put("start", _state.value.reminderStart).put("end", _state.value.reminderEnd))))
            val announcementPrefs = optionalRefresh { if(signedIn) metadata("/api/user/announcements") else JSONObject().put("enabled", prefs.getBoolean("announcements", true)) }.readOr(JSONObject().put("enabled", _state.value.announcementsOn))
            val favs = laundryPrefs.optJSONArray("favorites") ?: JSONArray()
            if(version != generation.get()) return@launch
            _state.update { it.copy(announcementsOn = announcementPrefs.optBoolean("enabled", true) && prefs.getBoolean("announcements", true), announcements = if (announcementPrefs.optBoolean("enabled", true) && prefs.getBoolean("announcements", true)) it.announcements else emptyList(), favorites = (0 until favs.length()).map { favs.optString(it) }.toSet(), favoriteRemindersOn = laundryPrefs.optBoolean("enabled"), reminderStart = laundryPrefs.optJSONArray("timeWindows")?.optJSONObject(0)?.str("start", "19:00") ?: "19:00", reminderEnd = laundryPrefs.optJSONArray("timeWindows")?.optJSONObject(0)?.str("end", "22:00") ?: "22:00") }
            if(signedIn) optionalRefresh { loadNoticesNow(version) }.exceptionOrNull()?.let { problems += it }
            if(_state.value.tab == 1 && signedIn) optionalRefresh { loadSocialNow(version) }.exceptionOrNull()?.let { problems += it }
            if (version == generation.get()) platform.synchronize(_state.value)
            if(building.isNotBlank() && _state.value.announcementsOn) {
                val announcements = metadata("/api/announcements?dorm=${api.encoded(building)}", auth = false).optJSONArray("announcements")?.objects().orEmpty().filter { announcementPrefs.optJSONObject("dismissed")?.str(it.str("id")) != it.str("version") && prefs.getString("dismiss.${profile?.id ?: "guest"}.${it.str("id")}", null) != it.str("version") && !seenAnnouncements.contains(it.str("id") + ":" + it.str("version")) }
                if(version == generation.get()) _state.update { it.copy(announcements = if (it.announcementsOn && prefs.getBoolean("announcements", true)) announcements else emptyList()) }
            }
            if (version == generation.get() && problems.isNotEmpty()) _state.update { it.copy(error = "机器状态已更新，部分附加信息暂未刷新。\n" + problems.first().message.orEmpty()) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (version == generation.get()) {
                val cached = if (signedIn && !authResolved && session.read() != null) api.cachedProfile() ?: _state.value.profile else null
                _state.update { if (signedIn && !authResolved && session.read() != null) it.copy(
                    profile = cached, authReady = cached != null, sessionRestoring = false,
                    authError = "账号恢复暂未完成，已保留本机登录状态。请检查网络后重试。",
                    error = e.message ?: "账号恢复暂未完成") else it.copy(error = e.message ?: "加载失败") }
            }
        }
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
        try { block(); metadata.clear(); channelInbox.invalidate(); if(success != null) message(success); refresh() }
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
    fun testMobileNotification() = mutate(null) {
        if (!platform.notificationsEnabled) {
            message("请先开启系统洗衣提醒，并在系统设置中允许 WeShirt 通知。")
            return@mutate
        }
        val result = api.call("/api/user/test-notify", "POST", mobileTestNotificationBody(session.installationId))
        message(mobileTestNotificationMessage(result))
    }
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
        val guest = session.read() == null
        if (!value) prefs.edit().remove("pendingGuestNotifyEnable").commit()
        else if (guest) prefs.edit().putBoolean("pendingGuestNotifyEnable", true).commit()
        if (!value || guest) {
            val status = platform.enableNotifications(value)
            _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
            message(status)
        }
        return mutate(null, wait = true) {
            if (intent != notificationIntent.get() || account != generation.get()) return@mutate
            if (session.read() != null) api.call("/api/user/notify", "POST", JSONObject().put("on", value))
            if (value && intent == notificationIntent.get() && account == generation.get()) {
                val status = platform.enableNotifications(true)
                _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
                if (session.read() != null) prefs.edit().remove("pendingGuestNotifyEnable").commit()
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
    private fun channelAccount(): ChannelAccountScope? = _state.value.profile?.id?.takeIf { it.isNotBlank() }?.let {
        ChannelAccountScope(_state.value.endpoint, it, generation.get())
    }
    fun refreshChannels() {
        if (channelAccount() == null || channelsJob?.isActive == true) return
        channelsJob = viewModelScope.launch {
            val account = channelAccount() ?: return@launch
            try {
                val channels = channelInbox.load(account) { channelAccount() == account }
                if (channelAccount() != account) return@launch
                _state.update { before ->
                    val selected = before.channel.takeIf { code -> channels.any { it.code == code } }
                        ?: channels.firstOrNull()?.code ?: "public"
                    before.copy(channels = channels, channel = selected,
                        messages = if (selected == before.channel) before.messages else emptyList())
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (problem: Exception) { if (channelAccount() == account) _state.update { it.copy(error = problem.message ?: "频道加载失败") } }
        }
    }
    /** Called by the foreground conversation viewport, never by directory fetch or prefetch. */
    suspend fun readVisibleMessages(channel: String, ids: List<String>): Boolean {
        val account = channelAccount() ?: return false
        fun current() = channelAccount() == account && _state.value.tab == 2 && _state.value.channel == channel
        if (!current()) return false
        // Recheck membership against the current scope; stale UI rows cannot mark another chat read.
        val visible = visibleIncomingMessageIds(_state.value.messages, ids)
        return try {
            val unread = channelInbox.acknowledge(account, channel, visible, ::current) ?: return true
            if (current()) _state.update { it.copy(
                channels = it.channels.map { row -> if (row.code == channel) row.copy(unreadCount = unread) else row },
                messageNotices = it.messageNotices.filterNot { notice -> notice.str("channel") == channel && notice.str("messageId") in visible }
            ) }
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: Exception) {
            if (problem is ApiException && problem.status == 404 && current()) refreshMessages()
            false // Keep unread intact; deleted IDs disappear after refreshing current rows.
        }
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
        generation.incrementAndGet(); channelInbox.clear(); metadata.clear(); channelsJob?.cancel()
        prefs.edit().remove("pendingGuestNotifyEnable").commit()
        com.laoke.weshirt.platform.login.DingTalkLoginBridge.clear(getApplication())
        try {
            platform.beforeSessionCleared()
            api.logout()
        } finally {
            session.clear(); platform.clearLocal()
            _state.update { it.copy(profile = null, tasks = emptyList(), social = JSONObject(), access = JSONObject(), channels = emptyList(), messages = emptyList(), messageNotices = emptyList(), queueNotices = emptyList(), channelConnected = false, favorites = emptySet(), notificationsOn = platform.notificationsEnabled, authReady = true, sessionRestoring = false, authError = null) }
        }
    }
}
