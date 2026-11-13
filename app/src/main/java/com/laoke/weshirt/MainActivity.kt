package com.laoke.weshirt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.laoke.weshirt.data.str
import com.laoke.weshirt.platform.login.*
import com.laoke.weshirt.platform.NativeNavigation
import com.laoke.weshirt.ui.WeShirtApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var notificationRequest: Long? = null
    private var loginResumeFeedback: Job? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val expected = notificationRequest
        notificationRequest = null
        if (expected != null) vm.notificationPermissionResult(expected, granted)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRequest = savedInstanceState?.takeIf { it.containsKey("notificationRequest") }?.getLong("notificationRequest")
        enableEdgeToEdge()
        consumeLogin(intent)
        consumeNavigation(intent)
        setContent { WeShirtApp(vm, onLogin = ::login, onRequestNotifications = ::requestNotifications, onRequestBackground = ::requestBackground) }
    }
    override fun onSaveInstanceState(outState: Bundle) { notificationRequest?.let { outState.putLong("notificationRequest", it) }; super.onSaveInstanceState(outState) }
    private fun requestNotifications() {
        getSharedPreferences("permission_guidance", MODE_PRIVATE).edit().putBoolean("notificationOffered", true).apply()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequest = vm.prepareNotificationPermission()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.toggleNotify(true)
    }
    private fun requestBackground() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try { startActivity(request) }
        catch (_: RuntimeException) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: RuntimeException) { vm.message("请在系统设置中打开 WeShirt 的电池与后台运行设置") }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeLogin(intent); consumeNavigation(intent) }
    override fun onResume() {
        super.onResume()
        consumeLogin(intent)
        loginResumeFeedback?.cancel()
        loginResumeFeedback = lifecycleScope.launch {
            // Some clients deliver the SDK callback shortly after bringing the
            // task forward. Give that delivery a chance before offering a retry.
            delay(1500)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                DingTalkLoginBridge.resumeFeedback(this@MainActivity)?.let(vm::message)
        }
    }
    override fun onStop() { loginResumeFeedback?.cancel(); DingTalkLoginBridge.noteBackground(this); super.onStop() }
    private fun consumeNavigation(intent: Intent?) {
        NativeNavigation.consumeTab(intent)?.let(vm::tab)
        val value = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) else null
            else -> null
        }
        if (value != null) {
            intent?.data = null; intent?.removeExtra(Intent.EXTRA_TEXT); intent?.action = Intent.ACTION_MAIN
            vm.receiveShare(value)
        }
    }
    private fun login() {
        vm.mutate(null, allowRestoring = true) {
            try {
                vm.api.beginNativeLogin()
                vm.clearFeedback()
                val request = DingTalkLoginBridge.createRequest(this, vm.session.installationId)
                check(getSharedPreferences("native_login_origin", MODE_PRIVATE).edit().putString("origin", vm.api.baseUrl).commit()) { DingTalkLoginBridge.failureMessage("LOGIN_STORAGE_FAILED") }
                val j = vm.api.call("/api/mobile/auth/challenge", "POST", JSONObject().put("installationId", request.installationId).put("state", request.state).put("codeChallenge", request.codeChallenge).put("platform", "android").put("packageName", "com.laoke.weshirt"), auth = false)
                val provider = j.optJSONObject("provider") ?: JSONObject()
                val result = DingTalkLoginBridge.begin(this, NativeLoginChallenge(j.str("challengeId"), j.str("state"), provider.str("clientId"), provider.str("redirectUri"), System.currentTimeMillis() + j.optLong("expiresIn", 300) * 1000, provider.str("scope", "openid")))
                if(result is LoginLaunchResult.Unavailable) { DingTalkLoginBridge.clear(this); vm.message(result.message) }
            } catch (problem: Exception) { DingTalkLoginBridge.clear(this); throw problem }
        }
    }
    private fun consumeLogin(intent: Intent?) {
        val origin = getSharedPreferences("native_login_origin", MODE_PRIVATE).getString("origin", null)
        val reply = try { DingTalkLoginBridge.consumeReply(this, intent) }
            catch (problem: IllegalStateException) { vm.message(problem.message ?: DingTalkLoginBridge.failureMessage("LOGIN_STORAGE_FAILED")); return }
            ?: return
        getSharedPreferences("native_login_origin", MODE_PRIVATE).edit().clear().commit()
        if(origin == null || origin != vm.api.baseUrl) { vm.message(DingTalkLoginBridge.failureMessage("LOGIN_ORIGIN_CHANGED", reply.eventId)); return }
        if(reply.authCode == null) { vm.message(DingTalkLoginBridge.failureMessage(reply.errorCode ?: "LOGIN_CALLBACK_FAILED", reply.eventId)); return }
        vm.mutate("登录成功", wait = true, allowRestoring = true) {
            check(origin == vm.api.baseUrl) { DingTalkLoginBridge.failureMessage("LOGIN_ORIGIN_CHANGED", reply.eventId) }
            // Also isolate callbacks restored after process death or app upgrade.
            // Old refresh/401 responses must not cancel a valid one-time code.
            vm.api.beginNativeLogin(origin)
            vm.clearFeedback()
            val j = vm.api.call("/api/mobile/auth/exchange", "POST", JSONObject().put("challengeId", reply.challengeId).put("state", reply.state).put("codeVerifier", reply.codeVerifier).put("authCode", reply.authCode), auth = false)
            vm.api.saveSession(j, origin)
            vm.platformSessionChanged()
        }
    }
}
