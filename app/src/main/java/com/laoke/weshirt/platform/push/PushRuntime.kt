package com.laoke.weshirt.platform.push

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.laoke.weshirt.platform.login.DingTalkLoginBridge
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.LaundryReminders
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.UUID

data class FirebasePushConfig(val applicationId: String, val apiKey: String, val projectId: String, val senderId: String) {
    fun configured(): Boolean = listOf(applicationId, apiKey, projectId, senderId).all { it.isNotBlank() }
}
data class UmengPushConfig(
    val appKey: String,
    val messageSecret: String,
) { fun configured(): Boolean = appKey.isNotBlank() && messageSecret.isNotBlank() }

data class PushConfiguration(
    val consent: Boolean = false,
    val region: PushRegion = PushRegion.AUTO,
    val firebase: FirebasePushConfig? = null,
    val umeng: UmengPushConfig? = null,
)

object PushRuntime {
    private const val PREFS = "push_installation"
    private const val FCM_TIMEOUT_MILLIS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile private var sink: PushRegistrationSink? = null
    @Volatile private var listener: ((NativePushEvent) -> Unit)? = null
    private var sdk: PushSdk = AndroidPushSdk()
    private var generation = 0L
    private data class Attempt(val generation: Long, val installation: String, val provider: PushProvider)
    private var attempt: Attempt? = null
    private var configuration: PushConfiguration? = null
    private var fcmTimeout: Job? = null
    private var fcmTimeoutMillis = FCM_TIMEOUT_MILLIS
    private var fcmPending = false
    private var pendingToken: String? = null
    private data class Receipt(val attempt: Attempt, val token: String, val sink: PushRegistrationSink)
    private var registrationReceipt: Receipt? = null
    @Volatile var route: PushRoute = PushRoute(PushProvider.NONE, "NOT_INITIALIZED")
        private set
    @Volatile var registrationStatus: String = "DISABLED"
        private set

    init { scope.launch { for (command in registrationQueue) runCatching { command() } } }

    @Synchronized fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("installation", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation", it).commit()
        }
    }

    @Synchronized fun useInstallationId(context: Context, installationId: String) {
        require(installationId.isNotBlank() && installationId.length <= 128)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("installation", null)?.let { it != installationId } == true) clearLocalSession(context)
        prefs.edit().putString("installation", installationId).commit()
    }

    /** Call after loading local privacy choices. With no consent/configuration no third-party SDK starts. */
    @Synchronized fun initialize(
        context: Context,
        configuration: PushConfiguration,
        registrationSink: PushRegistrationSink? = null,
        onEvent: (NativePushEvent) -> Unit = {},
    ): PushRoute {
        val app = context.applicationContext
        val previousSink = sink
        sink = registrationSink
        listener = onEvent
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val available = availability(app, configuration)
        val failureAt = prefs.getLong("fcmFailureAt", 0L)
        val now = System.currentTimeMillis()
        val deferred = PushRouter.deferFcm(failureAt, now)
        if (failureAt != 0L && !deferred) prefs.edit().remove("fcmFailureAt").commit()
        val chosen = PushRouter.choose(configuration.region, available, deferred)
        val current = attempt
        val reusable = this.configuration == configuration && current != null &&
            current.installation == installationId(app) && configuration.consent &&
            (chosen.provider == route.provider || route.provider == PushProvider.UMENG) &&
            registrationStatus !in setOf("UMENG_REGISTRATION_FAILED", "SDK_INITIALIZATION_FAILED")
        if (reusable) {
            if (previousSink !== registrationSink) registrationReceipt = null
            synchronizeToken(app)
            return route
        }
        activate(app, configuration, chosen)
        return route
    }

    @Synchronized fun onSessionAvailable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("session", true).commit()
        val token = pendingToken
        val current = attempt
        if (current != null && token != null) receiveToken(context.applicationContext, current, token)
        synchronizeToken(context.applicationContext)
    }

    fun isSessionActive(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("session", false)
    fun notificationsAllowed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        it.getBoolean("session", false) && it.getBoolean("consent", false)
    }

    private fun availability(context: Context, config: PushConfiguration) = PushAvailability(config.consent,
        runCatching { sdk.googleServicesAvailable(context) }.getOrDefault(false),
        config.firebase?.configured() == true, config.umeng?.configured() == true)

    private fun invalidateAttempt() {
        generation++
        attempt = null
        fcmTimeout?.cancel()
        fcmTimeout = null
        fcmPending = false
        pendingToken = null
        registrationReceipt = null
    }

    private fun current(context: Context, value: Attempt): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return attempt == value && configuration?.consent == true && prefs.getBoolean("consent", false) &&
            prefs.getString("installation", null) == value.installation && prefs.getString("provider", "") == value.provider.name
    }

    /** Caller holds the runtime monitor, including synchronous SDK failure callbacks. */
    private fun activate(context: Context, config: PushConfiguration, chosen: PushRoute) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousProvider = prefs.getString("provider", PushProvider.NONE.name)
        val previousConfiguration = configuration
        invalidateAttempt()
        configuration = config
        route = chosen
        val identity = installationId(context)
        if (chosen.provider != PushProvider.NONE) attempt = Attempt(generation, identity, chosen.provider)
        val removeToken = previousProvider != chosen.provider.name || previousConfiguration != config
        prefs.edit().putBoolean("consent", config.consent).putString("provider", chosen.provider.name).also {
            if (removeToken) it.remove("token")
        }.commit()
        if (removeToken && previousProvider != PushProvider.NONE.name && prefs.getBoolean("session", false)) {
            val unregisterGeneration = generation
            val unregisterSink = sink
            registrationQueue.trySend {
                val valid = synchronized(this) { generation == unregisterGeneration && installationId(context) == identity }
                if (valid) runCatching { unregisterSink?.unregister(identity) }
                Unit
            }
        }
        setComponents(context, fcmComponents, chosen.provider == PushProvider.FCM)
        setComponents(context, umengComponents, chosen.provider == PushProvider.UMENG)
        if (chosen.provider != PushProvider.FCM) runCatching { sdk.stopFirebase() }
        if (chosen.provider != PushProvider.UMENG) runCatching { sdk.stopUmeng(context) }
        registrationStatus = if (chosen.provider == PushProvider.NONE) chosen.reason else "REGISTERING"
        val started = attempt ?: return
        if (chosen.provider == PushProvider.FCM) startFirebase(context, config, started)
        else try {
            sdk.startUmeng(context, requireNotNull(config.umeng),
                { receiveToken(context, started, it) }, { failed(context, started) },
                { data -> synchronized(this) { if (current(context, started)) onMessage(context, PushProvider.UMENG, data) } })
        } catch (_: RuntimeException) { failed(context, started) }
    }

    private fun startFirebase(context: Context, config: PushConfiguration, started: Attempt) {
        fcmPending = true
        val timeout = fcmTimeoutMillis
        fcmTimeout = scope.launch { delay(timeout); failed(context, started) }
        try {
            sdk.startFirebase(context, requireNotNull(config.firebase),
                { receiveToken(context, started, it) }, { failed(context, started) })
        } catch (_: RuntimeException) { failed(context, started) }
    }

    @Synchronized private fun failed(context: Context, started: Attempt) {
        if (!current(context, started)) return
        if (started.provider != PushProvider.FCM) {
            registrationStatus = "UMENG_REGISTRATION_FAILED"
            return
        }
        if (!fcmPending) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("fcmFailureAt", System.currentTimeMillis()).commit()
        val config = configuration ?: return
        activate(context, config, PushRouter.afterFcmFailure(config.region, availability(context, config)))
    }

    @Synchronized private fun receiveToken(context: Context, started: Attempt, token: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!current(context, started)) return
        if (token.isBlank() || token.length > 4096) { failed(context, started); return }
        if (started.provider == PushProvider.FCM) {
            fcmPending = false
            fcmTimeout?.cancel()
            fcmTimeout = null
            prefs.edit().remove("fcmFailureAt").commit()
        }
        if (!prefs.getBoolean("session", false)) {
            pendingToken = token
            registrationStatus = "SESSION_REGISTRATION_PENDING"
            return
        }
        pendingToken = null
        val same = prefs.getString("token", null) == token
        prefs.edit().putString("token", token).commit()
        if (!same || registrationReceipt == null) registrationStatus = "TOKEN_RECEIVED"
        synchronizeToken(context)
    }

    /** Unstamped Firebase service callbacks trigger a fresh, stamped token read. */
    @Synchronized internal fun onFirebaseTokenChanged(context: Context) {
        val config = configuration ?: return
        val started = attempt ?: return
        if (!current(context, started) || !isSessionActive(context) || route.provider != PushProvider.FCM || fcmPending) return
        invalidateAttempt()
        val refreshed = Attempt(generation, installationId(context), PushProvider.FCM)
        attempt = refreshed
        registrationStatus = "REGISTERING"
        startFirebase(context.applicationContext, config, refreshed)
    }

    @Synchronized
    private fun synchronizeToken(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("session", false) || !prefs.getBoolean("consent", false)) return
        val started = attempt ?: return
        if (!current(context, started)) return
        val token = prefs.getString("token", null) ?: return
        val registrationSink = sink ?: run { registrationStatus = "SESSION_REGISTRATION_PENDING"; return }
        val receipt = Receipt(started, token, registrationSink)
        if (registrationReceipt == receipt) return
        registrationReceipt = receipt
        registrationQueue.trySend {
            val valid = synchronized(this) { current(context, started) && prefs.getBoolean("session", false) &&
                prefs.getString("token", null) == token && sink === registrationSink }
            if (!valid) return@trySend
            val result = runCatching { registrationSink.register(PushRegistration(started.installation, started.provider, token)) }
            synchronized(this) {
                if (current(context, started) && prefs.getBoolean("session", false) && prefs.getString("token", null) == token && sink === registrationSink) {
                    registrationStatus = if (result.isSuccess) "REGISTERED" else "REGISTRATION_PENDING"
                    if (result.isFailure) registrationReceipt = null
                }
            }
            Unit
        }
    }

    @Synchronized internal fun onMessage(context: Context, provider: PushProvider, data: Map<String, String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("session", false) || !prefs.getBoolean("consent", false) || prefs.getString("provider", "") != provider.name) return
        val event = parseEvent(data) ?: return
        if (event.kind == PushEventKind.TASK_FINISHED && event.taskId != null) LaundryReminders.confirmFinished(context, event.taskId)
        val notifications = LaundryNotifications(context)
        if (notifications.post(event) || notifications.wasHandled(event)) LaundryReminders.receivedRemotePhase(context, event)
        listener?.invoke(event)
    }

    fun parseEvent(data: Map<String, String>): NativePushEvent? = runCatching {
        NativePushEvent(requireNotNull(data["eventId"]), requireNotNull(data["revision"]).toLong(),
            PushEventKind.valueOf(requireNotNull(data["kind"])), data["title"].orEmpty(), data["body"].orEmpty(),
            data["taskId"], data["serverConfirmed"] == "true",
            data["finishAt"]?.takeIf { it.isNotBlank() }?.let { java.time.Instant.parse(it).toEpochMilli() }).takeIf { it.valid() }
    }.getOrNull()

    /** Call before invalidating the API session. Server logout must also revoke its installation association. */
    suspend fun logout(context: Context) {
        val snapshot = synchronized(this) {
            val oldSink = sink
            val identity = installationId(context)
            clearLocalSession(context)
            Triple(generation, identity, oldSink)
        }
        val removed = CompletableDeferred<Unit>()
        registrationQueue.send {
            val valid = synchronized(this) { generation == snapshot.first && !isSessionActive(context) }
            if (valid) runCatching { snapshot.third?.unregister(snapshot.second) }
            removed.complete(Unit)
            Unit
        }
        removed.await()
        synchronized(this) { if (generation == snapshot.first) registrationStatus = "SIGNED_OUT" }
    }

    @Synchronized fun clearLocalSession(context: Context) {
        invalidateAttempt()
        configuration = null
        sink = null
        listener = null
        route = PushRoute(PushProvider.NONE, "SIGNED_OUT")
        registrationStatus = "SIGNED_OUT"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("session", false)
            .putBoolean("consent", false).putString("provider", PushProvider.NONE.name).remove("token").commit()
        runCatching { sdk.stopFirebase() }
        runCatching { sdk.stopUmeng(context) }
        setComponents(context, fcmComponents + umengComponents, false)
        LaundryReminders.cancelAll(context, clearConfirmed = true)
        LaundryWidgetStore.clear(context)
        LaundryNotifications(context).clear()
        DingTalkLoginBridge.clear(context)
    }

    private fun setComponents(context: Context, names: List<String>, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        names.forEach { name ->
            val component = ComponentName(context.packageName, name)
            if (context.packageManager.getComponentEnabledSetting(component) != state)
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }

    private val fcmComponents = listOf("com.google.firebase.iid.FirebaseInstanceIdReceiver")
    private val umengComponents = listOf(
        "com.umeng.message.component.UmengMessageProvider", "com.umeng.message.component.UmengIntentService",
        "com.umeng.message.component.UmengMessageReceiverService", "com.umeng.message.component.UmengMessageHandlerService",
        "com.taobao.accs.ChannelService", "com.taobao.accs.ChannelService\$KernelService",
        "com.taobao.accs.data.MsgDistributeService", "com.taobao.accs.internal.AccsJobService",
        "org.android.agoo.accs.AgooService", "com.taobao.accs.ServiceReceiver",
    )
    /** Used by device tests to exercise SDK races without a network or real credentials. */
    @Synchronized internal fun replaceSdkForTest(context: Context, replacement: PushSdk, timeoutMillis: Long = FCM_TIMEOUT_MILLIS): PushSdk {
        require(timeoutMillis in 1..FCM_TIMEOUT_MILLIS)
        clearLocalSession(context)
        fcmTimeoutMillis = timeoutMillis
        return sdk.also { sdk = replacement }
    }
}

class WeShirtMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushRuntime.onFirebaseTokenChanged(this) }
    override fun onMessageReceived(message: RemoteMessage) { PushRuntime.onMessage(this, PushProvider.FCM, message.data) }
}
