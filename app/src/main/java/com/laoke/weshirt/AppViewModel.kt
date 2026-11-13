package com.laoke.weshirt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoke.weshirt.data.*
import com.laoke.weshirt.platform.PlatformCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val publicRooms = PublicRoomCache(java.io.File(app.cacheDir, "public-rooms"))
    private val assistantPreloader = AssistantPreloader({ path -> api.call(path) })
    private var channelsJob: kotlinx.coroutines.Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = -1
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
        val cachedProfile = api.cachedProfile()
        _state.update { it.copy(profile = cachedProfile, building = it.building.ifBlank { cachedProfile?.building.orEmpty() }, authReady = !restoring, sessionRestoring = restoring) }
        publicRooms.read(api.baseUrl, _state.value.building)?.let { cached ->
            _state.update { it.copy(machines = cached.machines, updatedAt = cached.updatedAt, boardWarning = cached.warning, boardFromCache = true) }
        }
        api.onMetric = { path, duration, status -> viewModelScope.launch {
            if(_state.value.telemetryOn) {
                val safePath = when { path.matches(Regex("/api/tasks/[^/]+/take")) -> "/api/tasks/:id/take"; path.matches(Regex("/api/tasks/[^/]+")) -> "/api/tasks/:id"; path in setOf("/api/machines","/api/tasks","/api/auth/me","/api/messages","/api/queue","/api/occupancy","/api/user/profile","/api/user/notify") -> path; else -> "/api/other" }
                if(telemetryBuffer.size < 20) telemetryBuffer.add(JSONObject().put("name","api_request").put("route",route()).put("endpoint",safePath).put("durationMs",duration).put("status",status))
            }
        } }
        api.onSessionInvalidated = {
            // Registration and reminder callers may invalidate from IO. Mutable UI
            // caches belong to Main, and a queued old failure must not erase a new login.
            val invalidated = api.sessionFence()
            val account = generation.get()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (api.sessionFence() != invalidated || generation.get() != account || session.read() != null) return@launch
                generation.incrementAndGet()
                channelInbox.clear(); metadata.clear(); channelsJob?.cancel(); assistantPreloader.clear()
                platform.clearLocal()
                coil.Coil.imageLoader(app).memoryCache?.clear()
                _state.update { it.copy(profile = null, tasks = emptyList(), channels = emptyList(), messages = emptyList(), messageNotices = emptyList(), queueNotices = emptyList(), channelConnected = false, social = JSONObject(), access = JSONObject(), favorites = emptySet(), queues = emptyList(), assistant = AssistantSnapshot(), notificationsOn = platform.notificationsEnabled, authReady = true, sessionRestoring = false, authError = null) }
                refresh()
            }
        }
        platform.initialize()
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        refresh()
    }
    fun platformSessionChanged() {
        generation.incrementAndGet(); metadata.clear(); channelInbox.clear(); channelsJob?.cancel(); assistantPreloader.clear(); platform.sessionChanged()
        val restoring = session.read() != null
        _state.update { it.copy(profile = api.cachedProfile(), channels = emptyList(), messages = emptyList(), messageNotices = emptyList(), channelConnected = false,
            tasks = emptyList(), social = JSONObject(), access = JSONObject(), favorites = emptySet(), queues = emptyList(), assistant = AssistantSnapshot(),
            authReady = !restoring, sessionRestoring = restoring, authError = null) }
        _state.update { it.copy(notificationsOn = platform.notificationsEnabled) }
        // Apply only an explicit, still-enabled guest request once. An older
        // device preference must not overwrite another account's server choice.
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
        if (value == 2 && _state.value.profile != null && _state.value.authReady) refreshChannels()
        else if (value == 4 && _state.value.authReady) preloadAssistant()
        else refresh()
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
        if (!_state.value.authReady && _state.value.profile != null) { message("账号仍在恢复，绑定住宿楼请稍后重试"); return }
        if (_state.value.profile == null) { generation.incrementAndGet(); prefs.edit().putString("building", code).apply(); val cached = publicRooms.read(api.baseUrl, code); _state.update { it.copy(building = code, machines = cached?.machines.orEmpty(), updatedAt = cached?.updatedAt, boardWarning = cached?.warning, boardFromCache = cached != null) }; refresh() }
        else mutate("住宿楼已更新") { api.call("/api/user/profile", "POST", JSONObject().put("dormBuilding", code)); generation.incrementAndGet(); assistantPreloader.clear(); prefs.edit().putString("building", code).apply(); _state.update { it.copy(building = code, machines = emptyList(), updatedAt = null, boardWarning = null, boardFromCache = false, assistant = AssistantSnapshot()) } }
    }
    /** Public room and authenticated features never wait for one another. */
    fun refresh(forceMetadata: Boolean = false): kotlinx.coroutines.Job = viewModelScope.launch {
        beginRefresh(forceMetadata).join()
    }
    /** Runs on the model's Main scope. A replaced generation never owns the new pass's loading state. */
    private fun beginRefresh(forceMetadata: Boolean): Job {
        if (forceMetadata) { metadata.clear(); channelInbox.invalidate(); invalidateAssistant() }
        val version = generation.get()
        refreshJob?.takeIf { it.isActive && refreshGeneration == version }?.let { current ->
            refreshPending = true
            return current
        }
        refreshJob?.cancel()
        refreshPending = false
        val task = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (api.baseUrl.isBlank()) { _state.update { it.copy(authReady = session.read() == null, sessionRestoring = false) }; return@launch }
                _state.update { it.copy(loading = true) }
                coroutineScope {
                    val room = async { refreshPublicRoom(version) }
                    val account = async { refreshAccount(version) }
                    room.await(); account.await()
                }
            } finally {
                if (refreshGeneration == version && refreshJob === coroutineContext[Job]) {
                    refreshJob = null
                    _state.update { it.copy(loading = false) }
                    flushTelemetry()
                    if (refreshPending && viewModelScope.coroutineContext[Job]?.isActive == true) {
                        refreshPending = false
                        refresh()
                    }
                }
            }
        }
        refreshGeneration = version
        refreshJob = task
        task.start()
        return task
    }
    private suspend fun refreshPublicRoom(version: Int) {
        try {
            coroutineScope {
                // Query the remembered room immediately, even while site metadata or auth is slow.
                val building = _state.value.building
                val board = async { if (building.isNotBlank()) refreshPublicBoard(version, building) }
                val site = async { optionalRefresh {
                    val response = metadata("/api/site", auth = false, ttlMs = 30_000)
                    val rows = response.optJSONArray("buildings") ?: throw ApiException(502, "site_missing", "client_" + UUID.randomUUID().toString().replace("-", ""), "楼栋目录暂未返回，请重试")
                    val buildings = rows.objects().filter { it.optBoolean("enabled", true) }.map { Building(it.str("code"), it.str("name")) }
                    if (version == generation.get()) _state.update { before ->
                        if (before.building.isNotBlank() && buildings.none { it.code == before.building })
                            before.copy(buildings = buildings, building = "", machines = emptyList(), updatedAt = null, boardFromCache = false, boardWarning = "原住宿楼当前不可用，请重新选择。")
                        else before.copy(buildings = buildings)
                    }
                } }
                board.await()
                // Optional metadata cannot erase a successfully published board or its diagnostics.
                site.await().exceptionOrNull()?.let { problem -> if (version == generation.get()) _state.update { it.copy(error = problem.message ?: "楼栋目录暂未更新") } }
            }
        } finally { if (version == generation.get()) _state.update { it.copy(loading = false) } }
    }
    private suspend fun refreshPublicBoard(version: Int, building: String) {
        try {
            val incoming = parseBoardSnapshot(api.call("/api/machines?dorm=${api.encoded(building)}", auth = false), building)
            if (version != generation.get() || _state.value.building != building) return
            val before = _state.value
            val board = preferLatestBoard(incoming, building, before.building, before.machines, before.updatedAt)
            _state.update { it.copy(machines = board.machines, updatedAt = board.updatedAt, boardWarning = board.warning, boardFromCache = false, error = null) }
            publicRooms.save(api.baseUrl, building, board)
            // Public care/status details are independent from session recovery too.
            val occupancy = optionalRefresh { api.call("/api/occupancy?dorm=${api.encoded(building)}", auth = false).optJSONObject("occupancy") }
            if (version == generation.get() && _state.value.building == building) {
                occupancy.getOrNull()?.let { rows -> _state.update { current -> current.copy(machines = current.machines.map { machine ->
                    val row = rows.optJSONObject(machine.id); val care = row?.optJSONObject("care")
                    machine.copy(note = care?.str("note") ?: machine.note, disinfectant = care?.optBoolean("useDisinfectant") ?: machine.disinfectant,
                        selfCleaned = care?.optBoolean("selfCleaned") ?: machine.selfCleaned, occupantId = row?.str("userId").orEmpty(),
                        suspectedUncollected = machine.suspectedUncollected || row?.optBoolean("suspectedUncollected") == true)
                }) } }
                occupancy.exceptionOrNull()?.let { problem -> _state.update { it.copy(boardWarning = "机器状态已更新，公开登记信息暂未更新。\n" + problem.message.orEmpty()) } }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: Exception) {
            if (version == generation.get() && _state.value.building == building) _state.update { it.copy(
                boardWarning = (if (it.machines.isNotEmpty()) "当前显示上次公开状态，更新暂未完成，请以现场为准。\n" else "机器状态暂未获取，可重试。\n") + problem.message.orEmpty()) }
        }
    }
    private suspend fun refreshAccount(version: Int) {
        val signedIn = session.read() != null
        val previouslyVerified = _state.value.authReady && signedIn
        if (!signedIn) {
            if (version == generation.get()) { _state.update { it.copy(profile = null, authReady = true, sessionRestoring = false, authError = null) }; refreshAnnouncements(version, null) }
            return
        }
        _state.update { it.copy(sessionRestoring = !it.authReady, authError = null) }
        try {
            val user = api.call("/api/auth/me").optJSONObject("user")
                ?: throw ApiException(502, "profile_missing", "client_" + UUID.randomUUID().toString().replace("-", ""), "账号资料暂未返回，请重试")
            if (user.str("id").isBlank()) throw ApiException(502, "profile_invalid", "client_" + UUID.randomUUID().toString().replace("-", ""), "账号资料暂不可用，请重试")
            // Restore authorization as well as display identity. A cached profile grants no access.
            val access = api.call("/api/user/permissions")
            if (version != generation.get()) return
            api.cacheProfile(user)
            val profile = Profile.parse(user)
            val target = profile.building.takeIf { it.isNotBlank() && (_state.value.buildings.isEmpty() || _state.value.buildings.any { row -> row.code == it }) } ?: _state.value.building
            val changed = target != _state.value.building
            val cached = if (changed) publicRooms.read(api.baseUrl, target) else null
            _state.update { it.copy(profile = profile, authReady = true, sessionRestoring = false, authError = null, access = access,
                building = target, machines = if (changed) cached?.machines.orEmpty() else it.machines,
                updatedAt = if (changed) cached?.updatedAt else it.updatedAt,
                boardWarning = if (changed) cached?.warning else it.boardWarning,
                boardFromCache = if (changed) cached != null else it.boardFromCache) }
            if (target.isNotBlank()) prefs.edit().putString("building", target).apply()
            if (prefs.getBoolean("pendingGuestNotifyEnable", false) && platform.notificationsEnabled) toggleNotify(true)
            if (changed && target.isNotBlank()) viewModelScope.launch { refreshPublicBoard(version, target) }
            if (_state.value.tab == 2) refreshChannels()
            preloadAssistant()
            val permissions = access.optJSONObject("permissions")
            val mayReadTasks = permissions?.optJSONObject("tasks")?.optBoolean("view", true) != false
            val mayReadQueue = target.isNotBlank() && permissions?.optJSONObject("queue")?.optBoolean("view", true) != false
            val results = coroutineScope {
                val tasks = async { optionalRefresh { if (mayReadTasks) api.call("/api/tasks") else JSONObject().put("tasks", JSONArray()) } }
                val queue = async { optionalRefresh { if (mayReadQueue) api.call("/api/queue?dorm=${api.encoded(target)}") else JSONObject().put("queue", JSONArray()) } }
                val laundry = async { optionalRefresh { metadata("/api/user/laundry-preferences").optJSONObject("preferences") ?: JSONObject() } }
                val announcements = async { optionalRefresh { metadata("/api/user/announcements") } }
                listOf(tasks.await(), queue.await(), laundry.await(), announcements.await())
            }
            if (version != generation.get() || !_state.value.authReady) return
            results[0].getOrNull()?.let { response ->
                val offset = epoch(response.str("serverNow"))?.minus(System.currentTimeMillis()) ?: 0L
                _state.update { it.copy(tasks = response.optJSONArray("tasks")?.objects().orEmpty().map { row -> LaundryTask.parse(row).copy(clockOffsetMs = offset) }) }
            }
            if (!mayReadTasks) _state.update { it.copy(tasks = emptyList()) }
            results[1].getOrNull()?.let { response -> _state.update { it.copy(queues = response.optJSONArray("queue")?.objects().orEmpty()) } }
            if (!mayReadQueue) _state.update { it.copy(queues = emptyList()) }
            results[2].getOrNull()?.let { laundry -> _state.update { it.copy(
                favorites = laundry.optJSONArray("favorites")?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() }.orEmpty(),
                favoriteRemindersOn = laundry.optBoolean("enabled"), reminderStart = laundry.optJSONArray("timeWindows")?.optJSONObject(0)?.str("start", "19:00") ?: "19:00",
                reminderEnd = laundry.optJSONArray("timeWindows")?.optJSONObject(0)?.str("end", "22:00") ?: "22:00") } }
            val announcementPrefs = results[3].getOrNull()
            announcementPrefs?.let { settings -> _state.update { it.copy(announcementsOn = settings.optBoolean("enabled", true) && prefs.getBoolean("announcements", true),
                announcements = if (settings.optBoolean("enabled", true) && prefs.getBoolean("announcements", true)) it.announcements else emptyList()) } }
            optionalRefresh { loadNoticesNow(version) }
            if (_state.value.tab == 1) optionalRefresh { loadSocialNow(version) }
            if (version == generation.get()) platform.synchronize(_state.value)
            refreshAnnouncements(version, announcementPrefs)
            val problem = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            if (version == generation.get() && problem != null) _state.update { it.copy(error = "部分账号信息暂未刷新。\n" + problem.message.orEmpty()) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: Exception) {
            if (version == generation.get() && session.read() != null) _state.update { it.copy(profile = api.cachedProfile() ?: it.profile,
                authReady = previouslyVerified, sessionRestoring = false,
                authError = "账号恢复暂未完成，已保留本机登录状态。洗衣房可继续查看，请稍后重试。\n" + problem.message.orEmpty()) }
        }
    }
    private suspend fun refreshAnnouncements(version: Int, settings: JSONObject?) {
        val building = _state.value.building
        if (building.isBlank() || !_state.value.announcementsOn) return
        optionalRefresh {
            val rows = metadata("/api/announcements?dorm=${api.encoded(building)}", auth = false).optJSONArray("announcements")?.objects().orEmpty().filter { row ->
                settings?.optJSONObject("dismissed")?.str(row.str("id")) != row.str("version") &&
                    prefs.getString("dismiss.${_state.value.profile?.id ?: "guest"}.${row.str("id")}", null) != row.str("version") && !seenAnnouncements.contains(row.str("id") + ":" + row.str("version")) }
            if (version == generation.get() && building == _state.value.building) _state.update { it.copy(announcements = if (it.announcementsOn) rows else emptyList()) }
        }
    }
    private fun assistantScope(): AssistantScope? = _state.value.takeIf { it.authReady }?.profile?.id?.takeIf(String::isNotBlank)?.let {
        AssistantScope(_state.value.endpoint, it, generation.get())
    }
    fun invalidateAssistant(names: Set<String> = AssistantPreloader.NAMES) { assistantScope()?.let { assistantPreloader.invalidate(it, names) } }
    fun acceptAssistantPanel(name: String, value: JSONObject) {
        val account = assistantScope() ?: return
        if (_state.value.access.optJSONObject("permissions")?.optJSONObject("assistant")?.optBoolean("view", true) == false) return
        val panel = assistantPreloader.accept(account, name, value)
        _state.update { it.copy(assistant = AssistantSnapshot(account, (it.assistant.takeIf { snapshot -> snapshot.scope == account }?.panels.orEmpty()) + (name to panel))) }
    }
    fun preloadAssistant(force: Boolean = false, names: Set<String> = AssistantPreloader.NAMES): kotlinx.coroutines.Job = viewModelScope.launch {
        val account = assistantScope() ?: return@launch
        if (_state.value.access.optJSONObject("permissions")?.optJSONObject("assistant")?.optBoolean("view", true) == false) {
            assistantPreloader.clear(); _state.update { it.copy(assistant = AssistantSnapshot()) }; return@launch
        }
        if (_state.value.assistant.scope != account) _state.update { it.copy(assistant = AssistantSnapshot(account)) }
        assistantPreloader.load(account, names, force, { assistantScope() == account }) { name, panel ->
            if (assistantScope() == account) _state.update { it.copy(assistant = AssistantSnapshot(account, it.assistant.panels + (name to panel))) }
        }
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
    fun mutate(success: String? = "已保存", wait: Boolean = false, allowRestoring: Boolean = false, block: suspend () -> Unit): kotlinx.coroutines.Job = viewModelScope.launch {
        if (!allowRestoring && session.read() != null && !_state.value.authReady) { message("账号仍在恢复，请稍后重试此操作"); return@launch }
        if(wait) mutationMutex.lock() else if(!mutationMutex.tryLock()) return@launch
        if (!allowRestoring && session.read() != null && !_state.value.authReady) { mutationMutex.unlock(); return@launch }
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
        if (!_state.value.authReady || _state.value.profile == null) return@launch
        if (!messagesLock.tryLock()) { messagesPending = true; return@launch }
        try { val version = generation.get(); loadMessagesNow(version); loadNoticesNow(version) }
        catch(cancel: CancellationException) { throw cancel }
        catch(problem: Exception) { message(problem.message.orEmpty()) }
        finally { messagesLock.unlock(); if (messagesPending) { messagesPending = false; refreshMessages() } }
    }
    fun moreMessages() = viewModelScope.launch {
        if (!_state.value.authReady || _state.value.profile == null) return@launch
        if (!messagesLock.tryLock()) return@launch
        try { if (_state.value.messagesHasMore) loadMessagesNow(generation.get(), _state.value.messagesPage + 1) }
        catch(cancel: CancellationException) { throw cancel }
        catch(problem: Exception) { message(problem.message.orEmpty()) }
        finally { messagesLock.unlock(); if (messagesPending) { messagesPending = false; refreshMessages() } }
    }
    private suspend fun loadNoticesNow(version: Int) {
        if (!_state.value.authReady || _state.value.profile == null || version != generation.get()) return
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
    private fun channelAccount(): ChannelAccountScope? = _state.value.takeIf { it.authReady }?.profile?.id?.takeIf { it.isNotBlank() }?.let {
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
        if (!_state.value.authReady || _state.value.profile == null) return
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
    fun logout() = mutate("已退出登录", allowRestoring = true) {
        generation.incrementAndGet(); channelInbox.clear(); metadata.clear(); channelsJob?.cancel(); assistantPreloader.clear()
        prefs.edit().remove("pendingGuestNotifyEnable").commit()
        com.laoke.weshirt.platform.login.DingTalkLoginBridge.clear(getApplication())
        try {
            platform.beforeSessionCleared()
            api.logout()
        } finally {
            session.clear(); platform.clearLocal()
            _state.update { it.copy(profile = null, tasks = emptyList(), social = JSONObject(), access = JSONObject(), channels = emptyList(), messages = emptyList(), messageNotices = emptyList(), queueNotices = emptyList(), channelConnected = false, favorites = emptySet(), assistant = AssistantSnapshot(), notificationsOn = platform.notificationsEnabled, authReady = true, sessionRestoring = false, authError = null) }
        }
    }
}
