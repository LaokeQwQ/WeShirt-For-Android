package com.laoke.weshirt.platform.login

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.android.dingtalk.openauth.AuthLoginParam
import com.android.dingtalk.openauth.DDAuthApiFactory
import com.android.dingtalk.openauth.utils.DDAuthConstant
import com.android.dingtalk.openauth.utils.DDAuthUtil
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class NativeLoginRequest(
    val installationId: String,
    val state: String,
    val codeChallenge: String,
)

data class NativeLoginChallenge(
    val challengeId: String,
    val state: String,
    val clientId: String,
    val redirectUri: String,
    val expiresAtMillis: Long,
    val scope: String = "openid",
)

sealed interface LoginLaunchResult {
    data object Opened : LoginLaunchResult
    data class Unavailable(val code: String, val message: String) : LoginLaunchResult
}

data class NativeLoginReply(
    val challengeId: String,
    val state: String,
    val codeVerifier: String,
    val authCode: String?,
    val errorCode: String?,
    val eventId: String,
)

/** Native app-to-app authorization only; the SDK's browser fallback is removed in the manifest. */
object DingTalkLoginBridge {
    const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"
    const val REPLY_EXTRA = "weshirt.native.login.reply"
    private const val PREFS = "native_login_pending"
    private const val MAX_AGE_MS = 5 * 60_000L

    @Synchronized
    fun createRequest(context: Context, installationId: String): NativeLoginRequest {
        require(installationId.isNotBlank())
        val state = randomToken()
        val verifier = randomToken()
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("state", state).putString("verifier", verifier)
            .putLong("created", System.currentTimeMillis()).commit()) { failureMessage("LOGIN_STORAGE_FAILED") }
        return NativeLoginRequest(installationId, state, challengeFor(verifier))
    }

    @Synchronized
    fun begin(activity: Activity, challenge: NativeLoginChallenge): LoginLaunchResult {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val created = prefs.getLong("created", 0L)
        if (!same(prefs.getString("state", null), challenge.state) ||
            now < created || now - created > MAX_AGE_MS || now >= challenge.expiresAtMillis) {
            return LoginLaunchResult.Unavailable("LOGIN_CHALLENGE_EXPIRED", failureMessage("LOGIN_CHALLENGE_EXPIRED"))
        }
        if (challenge.clientId.isBlank() || challenge.redirectUri.isBlank() ||
            challenge.challengeId.isBlank() || challenge.scope != "openid") {
            return LoginLaunchResult.Unavailable("LOGIN_NOT_CONFIGURED", failureMessage("LOGIN_NOT_CONFIGURED"))
        }
        if (!DDAuthUtil.isDDAppInstalled(activity, DINGTALK_PACKAGE)) {
            return LoginLaunchResult.Unavailable("DINGTALK_NOT_INSTALLED", failureMessage("DINGTALK_NOT_INSTALLED"))
        }
        if (!DDAuthUtil.isDDSupportAPI(activity, DINGTALK_PACKAGE)) {
            return LoginLaunchResult.Unavailable("DINGTALK_UPDATE_REQUIRED", failureMessage("DINGTALK_UPDATE_REQUIRED"))
        }
        if (!prefs.edit().putString("challenge", challenge.challengeId)
            .putLong("expires", minOf(challenge.expiresAtMillis, created + MAX_AGE_MS))
            .putBoolean("launched", true).commit()) {
            return LoginLaunchResult.Unavailable("LOGIN_STORAGE_FAILED", failureMessage("LOGIN_STORAGE_FAILED"))
        }
        return try {
            val params = AuthLoginParam.AuthLoginParamBuilder.newBuilder()
                .appId(challenge.clientId).redirectUri(challenge.redirectUri)
                .scope("openid").responseType("code").prompt("consent")
                .state(challenge.state).nonce(randomToken())
                .targetPackageName(DINGTALK_PACKAGE).forceTarget(true).build()
            DDAuthApiFactory.createDDAuthApi(activity, params).authLogin()
            LoginLaunchResult.Opened
        } catch (_: RuntimeException) {
            clear(activity)
            LoginLaunchResult.Unavailable("DINGTALK_LAUNCH_FAILED", failureMessage("DINGTALK_LAUNCH_FAILED"))
        }
    }

    @Synchronized
    internal fun acceptCallback(context: Context, intent: Intent): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_STATE)
        val now = System.currentTimeMillis()
        // Unsolicited/stale callbacks must never cancel another pending request.
        if (!prefs.getBoolean("launched", false) || prefs.getBoolean("ready", false) || !same(prefs.getString("state", null), state)) return false
        val callbackType = intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_TYPE)
        val expired = now < prefs.getLong("created", 0L) || now >= prefs.getLong("expires", 0L)
        val nativeCallback = callbackType == null || callbackType == DDAuthConstant.AUTH_LOGIN_TYPE_APP
        val code = if (!expired && nativeCallback) intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_AUTH_CODE)?.takeIf {
            it.isNotEmpty() && it.length <= 2048 && it.none { c -> c <= '\u0020' || c == '\u007f' }
        } else null
        val sdkError = intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_ERROR)
        val error = when {
            expired -> "LOGIN_CHALLENGE_EXPIRED"
            !nativeCallback -> "LOGIN_CALLBACK_TYPE_INVALID"
            code != null -> null
            sdkError == "-2" -> "LOGIN_CANCELLED"
            sdkError == "-4" -> "LOGIN_DENIED"
            else -> "LOGIN_CALLBACK_FAILED"
        }
        return prefs.edit().putBoolean("ready", true).putBoolean("launched", false)
            .putString("code", code).putString("error", error).putString("event", newEventId()).commit()
    }

    @Synchronized
    fun consumeReply(context: Context, intent: Intent?): NativeLoginReply? {
        // The private, state-validated result is the authority. A launcher intent
        // flag is only a wake-up hint and can disappear during task/process recovery.
        intent?.removeExtra(REPLY_EXTRA)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val expired = now < prefs.getLong("created", 0L) || now >= prefs.getLong("expires", 0L)
        if (!prefs.getBoolean("ready", false) && !(prefs.getBoolean("launched", false) && expired)) return null
        val error = if (expired) "LOGIN_CHALLENGE_EXPIRED" else prefs.getString("error", null)
        val reply = NativeLoginReply(prefs.getString("challenge", "")!!, prefs.getString("state", "")!!,
            prefs.getString("verifier", "")!!, if (error == null) prefs.getString("code", null) else null,
            error, prefs.getString("event", null) ?: newEventId())
        check(prefs.edit().clear().commit()) { failureMessage("LOGIN_STORAGE_FAILED") }
        return reply
    }

    @Synchronized
    fun noteBackground(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("launched", false)) prefs.edit().putBoolean("departed", true).commit()
    }

    @Synchronized
    fun resumeFeedback(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("launched", false) || !prefs.getBoolean("departed", false)) return null
        prefs.edit().putBoolean("departed", false).commit()
        // Keep the pending request: the SDK callback can still arrive after resume.
        return failureMessage("LOGIN_CALLBACK_PENDING")
    }

    fun failureMessage(code: String, eventId: String = newEventId()): String {
        val (status, message) = when (code) {
            "LOGIN_CANCELLED" -> 499 to "已取消钉钉登录"
            "LOGIN_DENIED" -> 403 to "钉钉授权被拒绝，请重新授权"
            "LOGIN_CHALLENGE_EXPIRED" -> 410 to "登录请求已过期，请重新点击钉钉登录"
            "LOGIN_ORIGIN_CHANGED" -> 409 to "服务地址已变更，请重新登录"
            "LOGIN_CALLBACK_PENDING" -> 408 to "尚未收到钉钉授权结果，可重新点击登录重试"
            "LOGIN_STORAGE_FAILED" -> 500 to "无法保存登录请求，请检查设备存储后重试"
            "LOGIN_NOT_CONFIGURED" -> 503 to "尚未配置钉钉原生登录"
            "DINGTALK_NOT_INSTALLED" -> 404 to "请先安装钉钉，再使用钉钉登录"
            "DINGTALK_UPDATE_REQUIRED" -> 426 to "当前钉钉版本不支持原生登录，请更新钉钉"
            "DINGTALK_LAUNCH_FAILED" -> 502 to "无法打开钉钉，请重试"
            else -> 400 to "钉钉授权未完成，请重新登录"
        }
        return "$message\n状态码 $status · 事件 $eventId"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("native_login_origin", Context.MODE_PRIVATE).edit().clear().commit()
    }

    internal fun challengeFor(verifier: String): String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    internal fun same(a: String?, b: String?): Boolean = a != null && b != null &&
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    private fun randomToken(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun newEventId(): String = "client_login_" + UUID.randomUUID().toString().replace("-", "")
}
