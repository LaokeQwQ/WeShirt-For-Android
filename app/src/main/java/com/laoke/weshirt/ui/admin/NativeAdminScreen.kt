package com.laoke.weshirt.ui.admin

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import org.json.JSONObject

private val adminSections = listOf(
    Triple("dashboard", "概览与同步", "adminDashboard"), Triple("users", "用户管理", "adminUsers"),
    Triple("machines", "机器与机位", "adminMachines"), Triple("messages", "内容治理", "adminMessages"),
    Triple("audits", "上报审计与日志", "adminAudit"), Triple("site", "网站与楼栋", "adminSite"),
    Triple("operations", "运营与通知", "adminOperations"), Triple("announcements", "开屏公告", "adminAnnouncements"),
    Triple("telemetry", "性能统计", "adminTelemetry"), Triple("assistant", "模型配置", "adminAssistant"),
    Triple("system", "上游与系统", "adminSystem"),
)

@Composable fun NativeAdminScreen(state: AppState, vm: AppViewModel, close: () -> Unit) {
    val owner = state.profile?.id.orEmpty()
    val binding = "$owner|${state.endpoint}"
    val scope = rememberCoroutineScope()
    val controller = remember(binding) { AdminController(vm.api, scope) { vm.state.value.profile?.id == owner && vm.state.value.endpoint == state.endpoint && vm.state.value.access.optBoolean("adminVisible") } }
    var section by remember(binding) { mutableStateOf("") }
    val permissions = state.access.optJSONObject("permissions") ?: JSONObject()
    val visible = adminSections.filter { permissions.optJSONObject(it.third)?.optBoolean("view") == true }
    val window = LocalActivity.current?.window
    DisposableEffect(controller) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { controller.close(); if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun goBack() { if (section.isBlank()) close() else section = "" }
    Dialog(onDismissRequest = ::goBack, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)) {
      Surface(Modifier.fillMaxSize()) {
       Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = ::goBack) { Text("返回") }
            Text(visible.find { it.first == section }?.second ?: "管理员后台", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = close) { Text("关闭") }
        }
        if (!state.access.optBoolean("adminVisible") || owner.isBlank()) { Text("当前账号没有后台权限", Modifier.padding(24.dp)); return@Column }
        controller.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        controller.info?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary) }
        if (controller.loading || controller.saving) Text(if (controller.saving) "正在提交…" else "正在读取…", Modifier.padding(16.dp))
        val resource = visible.find { it.first == section }?.third
        val canOperate = resource?.let { permissions.optJSONObject(it)?.optBoolean("operate") } == true
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (section.isBlank()) {
                items(visible.size) { index -> val item = visible[index]; AdminAction(item.second, !controller.saving) { section = item.first } }
                if (state.access.optJSONObject("identity")?.text("role") == "super") item { AdminAction("成员功能权限与范围", !controller.saving) { section = "permissions" } }
            } else item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (if (resource != null || section == "permissions" && state.access.optJSONObject("identity")?.text("role") == "super") section else "unavailable") {
                        "site" -> AdminSitePanel(controller, state, canOperate)
                        "permissions" -> AdminPermissionsPanel(controller, state)
                        "operations" -> AdminOperationsPanel(controller, state, canOperate)
                        "announcements" -> AdminAnnouncementsPanel(controller, state, canOperate)
                        "assistant" -> AdminAssistantPanel(controller, canOperate)
                        "system" -> AdminSystemPanel(controller, canOperate)
                        "telemetry" -> AdminTelemetryPanel(controller, state)
                        "users" -> AdminUsersPanel(controller, state, canOperate)
                        "machines" -> AdminMachinesPanel(controller, state, canOperate)
                        "messages" -> AdminModerationPanel(controller, state, canOperate)
                        "audits" -> AdminAuditPanel(controller, permissions.optJSONObject("adminSystem")?.optBoolean("operate") == true)
                        "dashboard" -> AdminDashboardPanel(controller, state, permissions.optJSONObject("adminSystem")?.optBoolean("operate") == true)
                        else -> Text("此功能的权限已改变，请返回重新选择。")
                    }
                }
            }
        }
       }
      }
    }
}
