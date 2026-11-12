@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.laoke.weshirt.ui.admin

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import org.json.JSONObject

private data class AdminSection(val key: String, val title: String, val permission: String, val group: String, val description: String, val icon: ImageVector)
private val adminSections = listOf(
    AdminSection("dashboard", "概览与同步", "adminDashboard", "日常管理", "数据状态与手动同步", Icons.Outlined.Dashboard),
    AdminSection("users", "用户管理", "adminUsers", "日常管理", "资料、住宿楼与账号", Icons.Outlined.PeopleOutline),
    AdminSection("machines", "机器与机位", "adminMachines", "日常管理", "登记、排队与 3D 机位", Icons.Outlined.LocalLaundryService),
    AdminSection("messages", "内容治理", "adminMessages", "日常管理", "聊天举报、留言与禁言", Icons.Outlined.Forum),
    AdminSection("operations", "运营与通知", "adminOperations", "公告与通知", "整点播报、待处理通知与诊断", Icons.Outlined.NotificationsNone),
    AdminSection("announcements", "开屏公告", "adminAnnouncements", "公告与通知", "Markdown 编辑与定时发布", Icons.Outlined.Campaign),
    AdminSection("site", "网站与楼栋", "adminSite", "网站与系统", "站点、楼栋、成员与钉钉配置", Icons.Outlined.Apartment),
    AdminSection("telemetry", "性能统计", "adminTelemetry", "网站与系统", "访问、接口与 3D 性能", Icons.Outlined.Insights),
    AdminSection("audits", "上报审计与日志", "adminAudit", "网站与系统", "事件追踪、上报凭证与日志导出", Icons.Outlined.History),
    AdminSection("assistant", "模型配置", "adminAssistant", "网站与系统", "助手端点与调用限制", Icons.Outlined.AutoAwesome),
    AdminSection("system", "上游与系统", "adminSystem", "网站与系统", "上游账号与连接诊断", Icons.Outlined.Dns),
)

@Composable fun NativeAdminScreen(state: AppState, vm: AppViewModel, close: () -> Unit) {
    val owner = state.profile?.id.orEmpty()
    val binding = "$owner|${state.endpoint}"
    val scope = rememberCoroutineScope()
    val controller = remember(binding) { AdminController(vm.api, scope) { vm.state.value.profile?.id == owner && vm.state.value.endpoint == state.endpoint && vm.state.value.access.optBoolean("adminVisible") } }
    var section by remember(binding) { mutableStateOf("") }
    val permissions = state.access.optJSONObject("permissions") ?: JSONObject()
    val visible = adminSections.filter { permissions.optJSONObject(it.permission)?.optBoolean("view") == true }
    val window = LocalActivity.current?.window
    DisposableEffect(controller) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { controller.close(); if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun goBack() { if (!controller.saving) { if (section.isBlank()) close() else section = "" } }
    Dialog(onDismissRequest = ::goBack, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)) {
      Surface(Modifier.fillMaxSize()) {
       Scaffold(topBar = {
           TopAppBar(title = { Text(if (section == "permissions") "成员功能权限与范围" else visible.find { it.key == section }?.title ?: "管理员后台", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
               navigationIcon = { IconButton(onClick = ::goBack, enabled = !controller.saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
               actions = { IconButton(onClick = close, enabled = !controller.saving) { Icon(Icons.Outlined.Close, "关闭后台") } })
       }) { padding -> Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        if (!state.access.optBoolean("adminVisible") || owner.isBlank()) { Text("当前账号没有后台权限", Modifier.padding(24.dp)); return@Column }
        controller.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        controller.info?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary) }
        if (controller.loading || controller.saving) Text(if (controller.saving) "正在提交…" else "正在读取…", Modifier.padding(16.dp))
        val resource = visible.find { it.key == section }?.permission
        val canOperate = resource?.let { permissions.optJSONObject(it)?.optBoolean("operate") } == true
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (section.isBlank()) {
                visible.groupBy { it.group }.forEach { (group, entries) ->
                    item { Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Card(Modifier.fillMaxWidth()) {
                        Column { entries.forEachIndexed { index, entry ->
                            AdminNavigationItem(entry.title, entry.description, entry.icon, permissions.optJSONObject(entry.permission)?.optBoolean("operate") != true) { section = entry.key }
                            if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
                        } }
                    } }
                }
                if (state.access.optJSONObject("identity")?.text("role") == "super") item {
                    Card(Modifier.fillMaxWidth()) { AdminNavigationItem("成员功能权限与范围", "逐项设置查看、操作和楼栋范围", Icons.Outlined.AdminPanelSettings) { section = "permissions" } }
                }
            } else item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!canOperate && resource != null) Text("当前为只读权限，可查看授权范围内的信息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
       } }
      }
    }
}

@Composable private fun AdminNavigationItem(title: String, description: String, icon: ImageVector, readOnly: Boolean = false, open: () -> Unit) {
    Surface(onClick = open, modifier = Modifier.fillMaxWidth().testTag("admin-open:$title"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description + if (readOnly) " · 只读" else "") },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}
