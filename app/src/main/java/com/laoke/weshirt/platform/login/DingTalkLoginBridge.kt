package com.laoke.weshirt.platform.login

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.android.dingtalk.openauth.AuthLoginParam
import com.android.dingtalk.openauth.DDAuthApiFactory
import com.android.dingtalk.openauth.utils.DDAuthConstant
import com.android.dingtalk.openauth.utils.DDAuthUtil
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("state", state).putString("verifier", verifier)
            .putLong("created", System.currentTimeMillis()).commit()
        return NativeLoginRequest(installationId, state, challengeFor(verifier))
    }

    @Synchronized
    fun begin(activity: Activity, challenge: NativeLoginChallenge): LoginLaunchResult {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val created = prefs.getLong("created", 0L)
        if (!same(prefs.getString("state", null), challenge.state) ||
            now < created || now - created > MAX_AGE_MS || now >= challenge.expiresAtMillis) {
            return LoginLaunchResult.Unavailable("LOGIN_CHALLENGE_EXPIRED", "登录请求已失效，请重新发起")
        }
        if (challenge.clientId.isBlank() || challenge.redirectUri.isBlank() ||
            challenge.challengeId.isBlank() || challenge.scope != "openid") {
            return LoginLaunchResult.Unavailable("LOGIN_NOT_CONFIGURED", "尚未配置钉钉原生登录")
        }
        if (!DDAuthUtil.isDDAppInstalled(activity, DINGTALK_PACKAGE)) {
            return LoginLaunchResult.Unavailable("DINGTALK_NOT_INSTALLED", "请先安装钉钉，再使用钉钉登录")
        }
        if (!DDAuthUtil.isDDSupportAPI(activity, DINGTALK_PACKAGE)) {
            return LoginLaunchResult.Unavailable("DINGTALK_UPDATE_REQUIRED", "当前钉钉版本不支持原生登录，请更新钉钉")
        }
        prefs.edit().putString("challenge", challenge.challengeId)
            .putLong("expires", minOf(challenge.expiresAtMillis, created + MAX_AGE_MS))
            .putBoolean("launched", true).commit()
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
            LoginLaunchResult.Unavailable("DINGTALK_LAUNCH_FAILED", "无法打开钉钉，请重试")
        }
    }

    @Synchronized
    internal fun acceptCallback(context: Context, intent: Intent): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_STATE)
        val now = System.currentTimeMillis()
        if (!prefs.getBoolean("launched", false) || prefs.getBoolean("ready", false) ||
            now >= prefs.getLong("expires", 0L) || !same(prefs.getString("state", null), state)) return false
        val callbackType = intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_TYPE)
        if (callbackType == DDAuthConstant.AUTH_LOGIN_TYPE_WEB) return false
        val code = intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_AUTH_CODE)?.takeIf { it.isNotBlank() && it.length <= 4096 }
        val sdkError = intent.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_ERROR)
        val error = when {
            code != null -> null
            sdkError == "-2" -> "LOGIN_CANCELLED"
            sdkError == "-4" -> "LOGIN_DENIED"
            else -> "LOGIN_CALLBACK_FAILED"
        }
        return prefs.edit().putBoolean("ready", true).putBoolean("launched", false)
            .putString("code", code).putString("error", error).commit()
    }

    @Synchronized
    fun consumeReply(context: Context, intent: Intent?): NativeLoginReply? {
        if (intent?.getBooleanExtra(REPLY_EXTRA, false) != true) return null
        intent.removeExtra(REPLY_EXTRA)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ready", false)) return null
        val reply = if (System.currentTimeMillis() < prefs.getLong("expires", 0L)) {
            NativeLoginReply(prefs.getString("challenge", "")!!, prefs.getString("state", "")!!,
                prefs.getString("verifier", "")!!, prefs.getString("code", null), prefs.getString("error", null))
        } else null
        prefs.edit().clear().commit()
        return reply
    }

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    internal fun challengeFor(verifier: String): String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    internal fun same(a: String?, b: String?): Boolean = a != null && b != null &&
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    private fun randomToken(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

class DingTalkCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(intent: Intent) {
        if (DingTalkLoginBridge.acceptCallback(this, intent)) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                it.putExtra(DingTalkLoginBridge.REPLY_EXTRA, true)
                startActivity(it)
            }
        }
        finish()
    }
}
