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
import com.laoke.weshirt.data.str
import com.laoke.weshirt.platform.login.*
import com.laoke.weshirt.platform.NativeNavigation
import com.laoke.weshirt.ui.WeShirtApp
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var notificationIdentity: String? = null
    private fun accountIdentity(): String? = vm.state.value.profile?.id?.let { vm.state.value.endpoint + "|" + it }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val expected = notificationIdentity
        notificationIdentity = null
        if (expected != null && expected == accountIdentity()) {
            if(granted) vm.toggleNotify(true) else vm.notificationPermissionDenied()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationIdentity = savedInstanceState?.getString("notificationIdentity")
        enableEdgeToEdge()
        consumeLogin(intent)
        consumeNavigation(intent)
        setContent { WeShirtApp(vm, onLogin = ::login, onRequestNotifications = ::requestNotifications, onRequestBackground = ::requestBackground) }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("notificationIdentity", notificationIdentity); super.onSaveInstanceState(outState) }
    private fun requestNotifications() {
        val identity = accountIdentity() ?: return vm.message("登录后可开启洗衣提醒")
        getSharedPreferences("permission_guidance", MODE_PRIVATE).edit().putBoolean("notificationOffered", true).apply()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationIdentity = identity
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.toggleNotify(true)
    }
    private fun requestBackground() {
        if (accountIdentity() == null) return
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try { startActivity(request) }
        catch (_: RuntimeException) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: RuntimeException) { vm.message("请在系统设置中打开 WeShirt 的电池与后台运行设置") }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeLogin(intent); consumeNavigation(intent) }
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
        vm.mutate(null) {
            val request = DingTalkLoginBridge.createRequest(this, vm.session.installationId)
            getSharedPreferences("native_login_origin", MODE_PRIVATE).edit().putString("origin", vm.api.baseUrl).commit()
            val j = vm.api.call("/api/mobile/auth/challenge", "POST", JSONObject().put("installationId", request.installationId).put("state", request.state).put("codeChallenge", request.codeChallenge).put("platform", "android").put("packageName", "com.laoke.weshirt"), auth = false)
            val provider = j.optJSONObject("provider") ?: JSONObject()
            val result = DingTalkLoginBridge.begin(this, NativeLoginChallenge(j.str("challengeId"), j.str("state"), provider.str("clientId"), provider.str("redirectUri"), System.currentTimeMillis() + j.optLong("expiresIn", 300) * 1000, provider.str("scope", "openid")))
            if(result is LoginLaunchResult.Unavailable) vm.message(result.message)
        }
    }
    private fun consumeLogin(intent: Intent?) {
        val origin = getSharedPreferences("native_login_origin", MODE_PRIVATE).getString("origin", null)
        val reply = DingTalkLoginBridge.consumeReply(this, intent) ?: return
        getSharedPreferences("native_login_origin", MODE_PRIVATE).edit().clear().commit()
        if(origin == null || origin != vm.api.baseUrl) { vm.message("服务地址已变更，请重新登录"); return }
        if(reply.authCode == null) { vm.message(if(reply.errorCode == "LOGIN_CANCELLED") "已取消登录" else "钉钉授权未完成，请重试"); return }
        vm.mutate("登录成功", wait = true) {
            check(origin == vm.api.baseUrl) { "服务地址已变更，请重新登录" }
            val j = vm.api.call("/api/mobile/auth/exchange", "POST", JSONObject().put("challengeId", reply.challengeId).put("state", reply.state).put("codeVerifier", reply.codeVerifier).put("authCode", reply.authCode), auth = false)
            vm.api.saveSession(j, origin)
            vm.platformSessionChanged()
        }
    }
}
