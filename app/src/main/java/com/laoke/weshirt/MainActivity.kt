package com.laoke.weshirt

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.laoke.weshirt.data.str
import com.laoke.weshirt.platform.login.*
import com.laoke.weshirt.ui.WeShirtApp
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) vm.toggleNotify(true) else vm.notificationPermissionDenied() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeLogin(intent)
        setContent { WeShirtApp(vm, onLogin = ::login, onRequestNotifications = { if(Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.toggleNotify(true) }) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeLogin(intent) }
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
