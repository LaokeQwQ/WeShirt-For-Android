package com.laoke.weshirt.platform.push

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.laoke.weshirt.platform.login.DingTalkLoginBridge
import com.laoke.weshirt.platform.notification.LaundryNotifications
import com.laoke.weshirt.platform.notification.LaundryReminders
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import com.umeng.commonsdk.UMConfigure
import com.umeng.message.PushAgent
import com.umeng.message.api.UPushMessageHandler
import com.umeng.message.api.UPushRegisterCallback
import com.umeng.message.api.UPushSettingCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile private var sink: PushRegistrationSink? = null
    @Volatile private var listener: ((NativePushEvent) -> Unit)? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var umengStarted = false
    @Volatile private var firebaseStarted = false
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("installation", installationId).commit()
    }

    /** Call after loading local privacy choices. With no consent/configuration no third-party SDK starts. */
    @Synchronized fun initialize(
        context: Context,
        configuration: PushConfiguration,
        registrationSink: PushRegistrationSink? = null,
        onEvent: (NativePushEvent) -> Unit = {},
    ): PushRoute {
        val app = context.applicationContext
        appContext = app
        sink = registrationSink
        listener = onEvent
        val googleAvailable = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
        val chosen = PushRouter.choose(configuration.region, PushAvailability(configuration.consent,
            googleAvailable, configuration.firebase?.configured() == true, configuration.umeng?.configured() == true))
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString("provider", PushProvider.NONE.name)
        if (previous != chosen.provider.name) {
            prefs.edit().remove("token").putString("provider", chosen.provider.name).commit()
            if (prefs.getBoolean("session", false)) registrationQueue.trySend { runCatching { sink?.unregister(installationId(app)) }; Unit }
        }
        route = chosen
        val umengEnabled = chosen.provider == PushProvider.UMENG
        setComponents(app, fcmComponents, chosen.provider == PushProvider.FCM)
        setComponents(app, umengComponents, umengEnabled)
        prefs.edit().putBoolean("consent", configuration.consent).putString("provider", chosen.provider.name).commit()
        registrationStatus = if (chosen.provider == PushProvider.NONE) chosen.reason else "REGISTERING"
        if (chosen.provider != PushProvider.FCM && firebaseStarted) FirebaseMessaging.getInstance().isAutoInitEnabled = false
        if (chosen.provider != PushProvider.UMENG && umengStarted) PushAgent.getInstance(app).disable(settingCallback)
        try {
            when (chosen.provider) {
                PushProvider.FCM -> initializeFirebase(app, requireNotNull(configuration.firebase))
                PushProvider.UMENG -> initializeUmeng(app, requireNotNull(configuration.umeng))
                PushProvider.NONE -> Unit
            }
        } catch (_: RuntimeException) { registrationStatus = "SDK_INITIALIZATION_FAILED" }
        return chosen
    }

    fun onSessionAvailable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("session", true).commit()
        synchronizeToken(context.applicationContext)
    }

    fun isSessionActive(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("session", false)
    fun notificationsAllowed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        it.getBoolean("session", false) && it.getBoolean("consent", false)
    }

    private fun initializeFirebase(context: Context, config: FirebasePushConfig) {
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApplicationId(config.applicationId)
                .setApiKey(config.apiKey).setProjectId(config.projectId).setGcmSenderId(config.senderId).build())
        }
        firebaseStarted = true
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().token.addOnSuccessListener { onToken(context, PushProvider.FCM, it) }
            .addOnFailureListener { registrationStatus = "FCM_REGISTRATION_FAILED" }
    }

    private fun initializeUmeng(context: Context, config: UmengPushConfig) {
        if (umengStarted) {
            PushAgent.getInstance(context).enable(settingCallback)
            PushAgent.getInstance(context).deviceToken?.takeIf { it.isNotBlank() }?.let { onToken(context, PushProvider.UMENG, it) }
            return
        }
        UMConfigure.enableImeiCollection(false)
        UMConfigure.enableImsiCollection(false)
        UMConfigure.enableIccidCollection(false)
        UMConfigure.enableWiFiMacCollection(false)
        UMConfigure.enableAplCollection(false)
        UMConfigure.enableInstallReferrerCollection(false)
        UMConfigure.setLogEnabled(false)
        UMConfigure.preInit(context, config.appKey, "private")
        UMConfigure.submitPolicyGrantResult(context, true)
        val agent = PushAgent.getInstance(context)
        agent.setPullUpEnable(false)
        agent.setAccsHeartbeatEnable(false)
        agent.setEnableJobHeartbeat(false)
        agent.setEnableAlarmHeartbeat(false)
        agent.setEnableForeground(context, false)
        agent.setPackageListenerEnable(false)
        agent.setLogUploadEnable(false)
        // All envelopes go through the same validator and ledger; SDK notification auto-display is bypassed.
        agent.setMessageHandler(UPushMessageHandler { _, message ->
            val custom = runCatching { JSONObject(message.custom ?: "{}") }.getOrNull()
            val data = message.extra.orEmpty().toMutableMap()
            custom?.keys()?.forEach { data[it] = custom.optString(it) }
            onMessage(context, PushProvider.UMENG, data)
        })
        UMConfigure.init(context, config.appKey, "private", UMConfigure.DEVICE_TYPE_PHONE, config.messageSecret)
        umengStarted = true
        agent.register(object : UPushRegisterCallback {
            override fun onSuccess(token: String) { onToken(context, PushProvider.UMENG, token) }
            override fun onFailure(code: String?, message: String?) { registrationStatus = "UMENG_REGISTRATION_FAILED" }
        })
    }

    internal fun onToken(context: Context, provider: PushProvider, token: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("consent", false) || prefs.getString("provider", "") != provider.name || token.isBlank()) return
        prefs.edit().putString("token", token).commit()
        registrationStatus = "TOKEN_RECEIVED"
        synchronizeToken(context)
    }

    private fun synchronizeToken(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("session", false) || !prefs.getBoolean("consent", false)) return
        val provider = runCatching { PushProvider.valueOf(prefs.getString("provider", "NONE")!!) }.getOrDefault(PushProvider.NONE)
        val token = prefs.getString("token", null) ?: return
        if (provider == PushProvider.NONE) return
        if (sink == null) { registrationStatus = "SESSION_REGISTRATION_PENDING"; return }
        registrationQueue.trySend {
            // A queued registration must not re-associate a token after logout or provider rotation.
            if (!prefs.getBoolean("session", false) || prefs.getString("provider", "") != provider.name ||
                prefs.getString("token", null) != token) return@trySend
            runCatching { requireNotNull(sink).register(PushRegistration(installationId(context), provider, token)) }
                .onSuccess { registrationStatus = "REGISTERED" }
                .onFailure { registrationStatus = "REGISTRATION_PENDING" }
            Unit
        }
    }

    internal fun onMessage(context: Context, provider: PushProvider, data: Map<String, String>) {
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("session", false).remove("token").commit()
        // Hide private surfaces immediately, even if the network is offline.
        clearLocalSession(context)
        if (firebaseStarted) { FirebaseMessaging.getInstance().isAutoInitEnabled = false; FirebaseMessaging.getInstance().deleteToken() }
        if (umengStarted) {
            PushAgent.getInstance(context).disable(settingCallback)
            umengStarted = false
        }
        setComponents(context, fcmComponents + umengComponents, false)
        val removed = CompletableDeferred<Unit>()
        registrationQueue.send { runCatching { sink?.unregister(installationId(context)) }; removed.complete(Unit); Unit }
        removed.await()
        registrationStatus = "SIGNED_OUT"
    }

    fun clearLocalSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("session", false).remove("token").commit()
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
    private val settingCallback = object : UPushSettingCallback {
        override fun onSuccess() = Unit
        override fun onFailure(code: String?, message: String?) = Unit
    }
}

class WeShirtMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushRuntime.onToken(this, PushProvider.FCM, token) }
    override fun onMessageReceived(message: RemoteMessage) { PushRuntime.onMessage(this, PushProvider.FCM, message.data) }
}
