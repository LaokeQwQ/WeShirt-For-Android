package com.laoke.weshirt.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.laoke.weshirt.data.AppState

/** Each automatic offer is made once per installation; settings always allows another attempt. */
@Composable
fun ReminderPermissionGuidance(
    state: AppState,
    showBackground: Boolean,
    closeBackground: () -> Unit,
    requestNotifications: () -> Unit,
    requestBackground: () -> Unit,
) {
    val context = LocalContext.current
    val choices = remember { context.getSharedPreferences("permission_guidance", Context.MODE_PRIVATE) }
    val preferences = remember { context.getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    var offerNotification by remember { mutableStateOf(false) }
    var offerBackground by remember { mutableStateOf(false) }
    var unrestricted by remember { mutableStateOf(backgroundUnrestricted(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) unrestricted = backgroundUnrestricted(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.profile?.id, state.notificationsOn, state.loading) {
        if (state.profile == null) { offerNotification = false; offerBackground = false; return@LaunchedEffect }
        if (state.loading) return@LaunchedEffect
        if (!state.notificationsOn && !preferences.contains("nativePushConsent") && !choices.getBoolean("notificationOffered", false)) {
            choices.edit().putBoolean("notificationOffered", true).apply()
            offerNotification = true
        }
        if (state.notificationsOn && !unrestricted && !choices.getBoolean("batteryOffered", false)) {
            choices.edit().putBoolean("batteryOffered", true).apply()
            offerBackground = true
        }
    }
    if (offerNotification && state.profile != null) AlertDialog(
        onDismissRequest = { offerNotification = false },
        title = { Text("开启洗衣提醒？") },
        text = { Text("结束前、设备确认结束以及有人 @ 你时通知你。接下来会申请系统通知权限，也可稍后在设置中开启。") },
        confirmButton = { TextButton(onClick = { offerNotification = false; requestNotifications() }) { Text("开启提醒") } },
        dismissButton = { TextButton(onClick = { offerNotification = false }) { Text("暂不开启") } },
    )
    if ((showBackground || offerBackground) && state.profile != null && !offerNotification) AlertDialog(
        onDismissRequest = { offerBackground = false; closeBackground() },
        title = { Text("后台提醒与省电设置") },
        text = { Text(if (unrestricted) "系统已允许 WeShirt 不受电池优化限制。部分手机仍有独立的自启动和后台运行设置；实际提醒时间以系统调度为准。"
            else "允许 WeShirt 不受电池优化限制，可减少洗衣提醒被系统延后的情况。接下来由系统确认；也可保持当前设置。部分手机还需要手动允许自启动。") },
        confirmButton = { TextButton(onClick = {
            offerBackground = false; closeBackground()
            if (!unrestricted) requestBackground()
        }) { Text(if (unrestricted) "知道了" else "申请省电白名单") } },
        dismissButton = { if (!unrestricted) TextButton(onClick = { offerBackground = false; closeBackground() }) { Text("稍后设置") } },
    )
}

private fun backgroundUnrestricted(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)
