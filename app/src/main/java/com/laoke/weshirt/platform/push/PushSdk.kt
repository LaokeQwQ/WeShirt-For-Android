package com.laoke.weshirt.platform.push

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.umeng.commonsdk.UMConfigure
import com.umeng.message.PushAgent
import com.umeng.message.api.UPushMessageHandler
import com.umeng.message.api.UPushRegisterCallback
import com.umeng.message.api.UPushSettingCallback
import org.json.JSONObject

/** SDK callbacks are accepted by PushRuntime only for the attempt that created them. */
internal interface PushSdk {
    fun googleServicesAvailable(context: Context): Boolean
    fun startFirebase(context: Context, config: FirebasePushConfig, token: (String) -> Unit, failed: () -> Unit)
    fun stopFirebase()
    fun startUmeng(context: Context, config: UmengPushConfig, token: (String) -> Unit, failed: () -> Unit, message: (Map<String, String>) -> Unit)
    fun stopUmeng(context: Context)
}

internal class AndroidPushSdk : PushSdk {
    private var firebaseStarted = false
    private var umengStarted = false

    override fun googleServicesAvailable(context: Context): Boolean =
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override fun startFirebase(context: Context, config: FirebasePushConfig, token: (String) -> Unit, failed: () -> Unit) {
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApplicationId(config.applicationId)
                .setApiKey(config.apiKey).setProjectId(config.projectId).setGcmSenderId(config.senderId).build())
        }
        firebaseStarted = true
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token(it) }.addOnFailureListener { failed() }
    }

    override fun stopFirebase() {
        if (firebaseStarted) FirebaseMessaging.getInstance().isAutoInitEnabled = false
        // Account logout unbinds this installation on the server. An asynchronous
        // deleteToken from an old account could delete the next account's token.
    }

    override fun startUmeng(context: Context, config: UmengPushConfig, token: (String) -> Unit, failed: () -> Unit, message: (Map<String, String>) -> Unit) {
        if (!umengStarted) {
            UMConfigure.enableImeiCollection(false)
            UMConfigure.enableImsiCollection(false)
            UMConfigure.enableIccidCollection(false)
            UMConfigure.enableWiFiMacCollection(false)
            UMConfigure.enableAplCollection(false)
            UMConfigure.enableInstallReferrerCollection(false)
            UMConfigure.setLogEnabled(false)
            UMConfigure.preInit(context, config.appKey, "private")
            UMConfigure.submitPolicyGrantResult(context, true)
        }
        val agent = PushAgent.getInstance(context)
        agent.setPullUpEnable(false)
        agent.setAccsHeartbeatEnable(false)
        agent.setEnableJobHeartbeat(false)
        agent.setEnableAlarmHeartbeat(false)
        agent.setEnableForeground(context, false)
        agent.setPackageListenerEnable(false)
        agent.setLogUploadEnable(false)
        agent.setMessageHandler(UPushMessageHandler { _, value ->
            val custom = runCatching { JSONObject(value.custom ?: "{}") }.getOrNull()
            val data = value.extra.orEmpty().toMutableMap()
            custom?.keys()?.forEach { data[it] = custom.optString(it) }
            message(data)
        })
        if (!umengStarted) {
            UMConfigure.init(context, config.appKey, "private", UMConfigure.DEVICE_TYPE_PHONE, config.messageSecret)
            umengStarted = true
        } else {
            agent.enable(object : UPushSettingCallback {
                override fun onSuccess() = Unit
                override fun onFailure(code: String?, message: String?) { failed() }
            })
        }
        agent.deviceToken?.takeIf(String::isNotBlank)?.let(token)
        agent.register(object : UPushRegisterCallback {
            override fun onSuccess(value: String) { token(value) }
            override fun onFailure(code: String?, message: String?) { failed() }
        })
    }

    override fun stopUmeng(context: Context) {
        if (umengStarted) PushAgent.getInstance(context).disable(object : UPushSettingCallback {
            override fun onSuccess() = Unit
            override fun onFailure(code: String?, message: String?) = Unit
        })
    }
}
