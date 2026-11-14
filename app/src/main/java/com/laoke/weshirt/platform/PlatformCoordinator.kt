package com.laoke.weshirt.platform

import android.content.Context
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.epoch
import com.laoke.weshirt.data.str
import com.laoke.weshirt.data.isArchived
import com.laoke.weshirt.data.isExpired
import com.laoke.weshirt.data.serviceEndpoint
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.LaundryReminders
import com.laoke.weshirt.platform.notification.TaskProgress
import com.laoke.weshirt.platform.push.*
import com.laoke.weshirt.platform.widget.LaundryWidgetRefresh
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import com.laoke.weshirt.platform.widget.projectLaundryWidget
import org.json.JSONObject

/** Joins native services to the same encrypted API session; no duplicate account/token store. */
class PlatformCoordinator(
    context: Context,
    private val session: SecureSession,
    private val api: ApiClient,
    private val onPush: () -> Unit,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val taskPrefs = app.getSharedPreferences("native_task_revisions", Context.MODE_PRIVATE)
    private var lastState: AppState? = null

    private val sink = object : PushRegistrationSink {
        override suspend fun register(registration: PushRegistration) {
            require(registration.installationId == session.installationId)
            api.call("/api/mobile/installations/${api.encoded(session.installationId)}", "PUT", JSONObject()
                .put("provider", if (registration.provider == PushProvider.FCM) "fcm" else "upush")
                .put("token", registration.token).put("notificationsEnabled", true).put("appVersion", BuildConfig.VERSION_NAME))
        }
        override suspend fun unregister(installationId: String) {
            if (session.read() == null || api.baseUrl.isBlank()) return
            api.call("/api/mobile/installations/${api.encoded(installationId)}", "PUT",
                JSONObject().put("notificationsEnabled", false).put("appVersion", BuildConfig.VERSION_NAME))
        }
    }

    /** Device choice and OS permission are available before an account signs in. */
    val notificationsEnabled: Boolean get() = prefs.getBoolean("nativePushConsent", false) &&
        LaundryNotifications(app).capabilities().let { it.permissionGranted && it.notificationsEnabled }

    fun initialize() {
        PushRuntime.useInstallationId(app, session.installationId)
        configurePush()
        if (session.read() != null) PushRuntime.onSessionAvailable(app) else PushRuntime.clearLocalSession(app)
    }

    private fun configurePush(): PushRoute = PushRuntime.initialize(app, configuration(app, session.read() != null), sink) { onPush() }

    fun enableNotifications(enabled: Boolean): String {
        prefs.edit().putBoolean("nativePushConsent", enabled).commit()
        val route = configurePush()
        if (session.read() != null) PushRuntime.onSessionAvailable(app)
        if (!notificationsEnabled) {
            LaundryReminders.cancelAll(app)
            LaundryNotifications(app).clear()
            return if (enabled) "请在系统设置中允许 WeShirt 通知" else "通知已关闭"
        }
        if (session.read() == null) return "通知已开启，登录后接收洗衣与消息提醒"
        lastState?.let(::synchronize)
        return if (route.provider == PushProvider.NONE) "本地提醒已开启，远程推送尚未配置" else "本地提醒已开启；远程推送正在登记"
    }

    fun sessionChanged() {
        clearLocal()
        configurePush()
        if (session.read() != null) PushRuntime.onSessionAvailable(app) else clearLocal()
    }

    /** Runs after a successful board refresh. No extra network calls or polling timer. */
    fun synchronize(state: AppState) {
        if (session.read() == null) { clearLocal(); return }
        if (!synchronizeWidget(state)) return
        val profile = state.profile ?: return
        lastState = state
        if (!PushRuntime.isSessionActive(app)) PushRuntime.onSessionAvailable(app)
        if (!profile.notifyOn) { LaundryReminders.cancelAll(app); LaundryNotifications(app).clear(); return }
        val active = LinkedHashMap<String, TaskProgress>()
        val permissions = state.access.optJSONObject("permissions")
        state.tasks.filter { !it.done && !it.isArchived(System.currentTimeMillis()) && !it.isExpired(System.currentTimeMillis()) && it.endAt != null && !LaundryReminders.isConfirmedFinished(app, it.id) }.forEach { task ->
            if (permissions?.optJSONObject("tasks")?.optBoolean("view", true) == false) return@forEach
            val finish = (task.endAt ?: return@forEach) - task.clockOffsetMs
            active[task.id] = tracked(task.id, task.name, finish, (task.startAt ?: 0L).let { if(it > 0) it - task.clockOffsetMs else 0L }, profile.notifyLeadMin)
        }
        state.queues.filter { it.optBoolean("mine") && it.optInt("position") == 1 && !it.optBoolean("uncertain") }.forEach { queue ->
            if (permissions?.optJSONObject("queue")?.optBoolean("view", true) == false) return@forEach
            val machine = state.machines.find { it.id == queue.str("machineId") }
            val finish = machine?.finishAt?.minus(machine.clockOffsetMs) ?: epoch(queue.str("eta")) ?: return@forEach
            val id = "queue:${queue.str("id")}"
            active[id] = tracked(id, "等候 · ${machine?.name ?: "预约机器"}", finish, 0L, 3)
        }
        val previous = taskPrefs.getString("activeIds", "")!!.split('|').filter { it.isNotBlank() }.toSet()
        (previous - active.keys).forEach { LaundryReminders.cancel(app, it); LaundryNotifications(app).cancelProgress(it); taskPrefs.edit().remove(it).apply() }
        active.values.forEach { progress ->
            if (notificationsEnabled) { LaundryReminders.schedule(app, progress); LaundryNotifications(app).updateProgress(progress) }
        }
        taskPrefs.edit().putString("activeIds", active.keys.joinToString("|")).apply()
    }

    /** Publishing public room data must not arrange private reminders a second time. */
    fun synchronizeWidget(state: AppState): Boolean {
        val scope = LaundryWidgetStore.captureScope(app)
        if (scope == null) { LaundryWidgetStore.clear(app); return false }
        // A cached profile is display-only while the account is being restored.
        // Keep the matching widget snapshot, without promoting it to fresh data.
        if (!state.authReady || state.profile == null || state.profile.id != scope.userId || state.endpoint.trimEnd('/') != scope.origin) return false
        // Home-screen widgets are an account feature, independent of notification consent.
        if (state.access.optJSONObject("permissions")?.optJSONObject("tasks")?.optBoolean("view", true) == false)
            LaundryWidgetStore.redactTask(app, scope)
        val widget = projectLaundryWidget(state)
        if (widget != null && LaundryWidgetStore.update(app, widget, scope)) LaundryWidgetRefresh.scheduleTicks(app)
        else if (state.building.isBlank()) LaundryWidgetStore.clear(app)
        return true
    }

    private fun tracked(id: String, machine: String, finish: Long, start: Long, leadMinutes: Int): TaskProgress {
        val previous = runCatching { JSONObject(taskPrefs.getString(id, "{}")!!) }.getOrDefault(JSONObject())
        val changed = previous.optLong("finish", -1) != finish
        val revision = previous.optLong("revision", 0) + if (changed) 1 else 0
        taskPrefs.edit().putString(id, JSONObject().put("finish", finish).put("revision", revision).toString()).apply()
        return TaskProgress(id, revision, machine, start, finish, userRequestedTracking = true, leadMinutes = leadMinutes)
    }

    suspend fun beforeSessionCleared() { LaundryWidgetRefresh.cancel(app); PushRuntime.logout(app); taskPrefs.edit().clear().commit(); lastState = null }
    fun clearLocal() { LaundryWidgetRefresh.cancel(app); PushRuntime.clearLocalSession(app); taskPrefs.edit().clear().commit(); lastState = null }

    companion object {
        /** Restores SDK state on a push-started process without constructing a second API client. */
        fun bootstrap(context: Context) {
            val session = SecureSession(context)
            val saved = session.read()
            val endpoint = serviceEndpoint(BuildConfig.DEBUG, BuildConfig.API_BASE_URL,
                context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("endpoint", null)).trimEnd('/')
            val active = saved != null && saved.str("origin") == endpoint
            if (saved != null && !active) session.clear()
            PushRuntime.useInstallationId(context, session.installationId)
            PushRuntime.initialize(context, configuration(context, active))
            if (active) PushRuntime.onSessionAvailable(context) else PushRuntime.clearLocalSession(context)
        }

        private fun configuration(context: Context, sessionActive: Boolean): PushConfiguration {
            val capabilities = LaundryNotifications(context).capabilities()
            val consent = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("nativePushConsent", false)
            return PushConfiguration(consent = consent && sessionActive && capabilities.permissionGranted && capabilities.notificationsEnabled,
                firebase = FirebasePushConfig(BuildConfig.PUSH_FCM_APP_ID, BuildConfig.PUSH_FCM_API_KEY,
                    BuildConfig.PUSH_FCM_PROJECT_ID, BuildConfig.PUSH_FCM_SENDER_ID),
                umeng = UmengPushConfig(BuildConfig.PUSH_UMENG_APP_KEY, BuildConfig.PUSH_UMENG_MESSAGE_SECRET))
        }
    }
}
