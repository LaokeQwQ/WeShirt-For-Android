package com.laoke.weshirt.platform.login

import android.content.Context
import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.SessionStore
import com.laoke.weshirt.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun nativeLoginPreparation(scope: CoroutineScope, context: Context, api: ApiClient, session: SessionStore) =
    NativeLoginPreparation(scope, load = { origin ->
        recoverNativeLoginChallenge(origin, { api.baseUrl }) { withContext(Dispatchers.IO) {
            if (api.baseUrl != origin || !DingTalkLoginBridge.canPrepare(context)) throw CancellationException("Login changed before preparation")
            DingTalkLoginBridge.availability(context)?.let { throw IllegalStateException(it.message) }
            val request = DingTalkLoginBridge.createRequest(context, session.installationId)
            check(context.getSharedPreferences("native_login_origin", Context.MODE_PRIVATE).edit().putString("origin", origin).commit()) {
                DingTalkLoginBridge.failureMessage("LOGIN_STORAGE_FAILED")
            }
            // Do not extend the server lifetime by a slow network response.
            val started = System.currentTimeMillis()
            val reply = api.call("/api/mobile/auth/challenge", "POST", JSONObject()
                .put("installationId", request.installationId).put("state", request.state).put("codeChallenge", request.codeChallenge)
                .put("platform", "android").put("packageName", "com.laoke.weshirt"), auth = false)
            if (api.baseUrl != origin) throw CancellationException("Login service changed")
            val provider = reply.optJSONObject("provider") ?: JSONObject()
            NativeLoginChallenge(reply.str("challengeId"), reply.str("state"), provider.str("clientId"), provider.str("redirectUri"),
                started + reply.optLong("expiresIn", 180).coerceIn(1, 300) * 1000, provider.str("scope", "openid"))
        } }
    }, valid = { prepared ->
        withContext(Dispatchers.IO) {
            if (api.baseUrl != prepared.origin) throw CancellationException("Login service changed")
            DingTalkLoginBridge.preparedMatches(context, prepared.challenge)
        }
    })
