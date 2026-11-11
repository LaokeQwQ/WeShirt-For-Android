package com.laoke.weshirt.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.R
import com.laoke.weshirt.data.*
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable fun SettingsScreen(state: AppState, vm: AppViewModel, login: () -> Unit, requestNotifications: () -> Unit, endpoint: () -> Unit, building: () -> Unit, backgroundSettings: () -> Unit = {}) {
    var nick by remember(state.profile?.nick) { mutableStateOf(state.profile?.nick.orEmpty()) }; var avatarMenu by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }; var reminderStart by remember(state.reminderStart) { mutableStateOf(state.reminderStart) }; var reminderEnd by remember(state.reminderEnd) { mutableStateOf(state.reminderEnd) }
    var admin by remember(state.endpoint, state.profile?.id) { mutableStateOf(false) }
    var favoriteSettings by remember(state.endpoint, state.profile?.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val owner = state.profile?.id?.let { state.endpoint + "|" + it }
    var avatarOwner by remember { mutableStateOf<String?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri != null && avatarOwner != null && avatarOwner == vm.state.value.let { s -> s.profile?.id?.let { s.endpoint + "|" + it } }) vm.mutate {
        val requestOwner = avatarOwner
        val data = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
            var sample = 1; while(bounds.outWidth/sample > 512 || bounds.outHeight/sample > 512) sample *= 2
            val raw = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("无法读取图片")
            val side = minOf(raw.width,raw.height); val square = Bitmap.createBitmap(raw,(raw.width-side)/2,(raw.height-side)/2,side,side); val resized = Bitmap.createScaledBitmap(square,128,128,true)
            val bytes = ByteArrayOutputStream().apply { resized.compress(Bitmap.CompressFormat.JPEG,82,this) }.toByteArray()
            if(resized !== square) resized.recycle(); if(square !== raw) square.recycle(); raw.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        }
        if(requestOwner != vm.state.value.let { s -> s.profile?.id?.let { s.endpoint + "|" + it } }) return@mutate
        vm.api.call("/api/user/profile","POST",JSONObject().put("avatarUrl",data))
    } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SettingsCard("资料与账号") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.size(64.dp).clip(CircleShape).clickable(enabled = state.profile != null) { avatarMenu = true }, color = MaterialTheme.colorScheme.secondaryContainer) { if(state.profile?.avatarUrl?.isNotBlank() == true) AsyncImage(state.profile.avatarUrl, "点击修改头像", Modifier.fillMaxSize()) else Icon(Icons.Outlined.PersonOutline, "头像", Modifier.padding(14.dp)) }
                Column { Text(state.profile?.nick ?: "还未登录", style = MaterialTheme.typography.titleLarge); Text(if(state.profile != null) "钉钉账号已关联" else "登录后同步你的洗衣记录", style = MaterialTheme.typography.bodySmall) }
            }
            if(state.profile == null) Button(onClick = login, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("钉钉登录") }
            else {
                OutlinedTextField(nick, { nick = it.take(16) }, label = { Text("昵称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { vm.nick(nick) }, enabled = !state.busy && nick.isNotBlank()) { Text("保存") }; OutlinedButton(onClick = { vm.randomNick { nick = it } }, enabled = !state.busy) { Text("随机昵称") } }
                TextButton(onClick = building, enabled = !state.busy && (state.profile.changesLeft > 0 || state.profile.building.isBlank())) { Icon(Icons.Outlined.Apartment, null); Text("住宿楼 · ${state.buildings.find { it.code == state.building }?.name ?: "请选择"}") }
                Text(if (state.profile.changesLeft > 0) "还可自行修改 ${state.profile.changesLeft} 次，之后请联系管理员。" else "自行修改次数已用完，换楼请联系管理员。", style = MaterialTheme.typography.bodySmall)
                Text("钉钉账号 · ${state.profile.dingNick.ifBlank { "已关联" }}")
                Text("手机号 · ${state.profile.mobile.ifBlank { "尚未获取" }}")
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { logout = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("退出登录") }
            }
            TextButton(onClick = endpoint) { Text("服务地址设置") }
        } }
        item { SettingsCard("通知") {
            ToggleRow("系统洗衣提醒", "结束前提醒、排队与被 @ 提示", state.notificationsOn) { if(it) requestNotifications() else vm.toggleNotify(false) }
            ToggleRow("开屏公告", "接收所在楼栋和公共公告", state.announcementsOn) { vm.toggleAnnouncements(it) }
            Text("优先使用 FCM，不可用时使用基础 U-Push。实际送达受网络和系统后台限制影响。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = backgroundSettings, enabled = state.profile != null) { Text("后台提醒与省电白名单") }
            Text("结束前提醒", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(0, 3, 5, 10).forEach { minutes -> FilterChip(state.profile?.notifyLeadMin == minutes, { vm.mutate { vm.api.call("/api/user/notify", "POST", JSONObject().put("leadMin", minutes)) } }, enabled = state.profile != null && !state.busy, label = { Text(if (minutes == 0) "结束时" else "${minutes}分") }) } }
            TextButton(onClick = { vm.mutate(null) { val result = vm.api.call("/api/user/test-notify", "POST", JSONObject()); vm.message(result.str("message", result.str("hint", "通知请求已提交，请检查钉钉和系统通知"))) } }, enabled = state.profile != null && !state.busy) { Text("发送测试提醒") }
            FilledTonalButton(onClick = { favoriteSettings = true }, enabled = state.profile != null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("常用机器与洗衣时段 · ${if (state.favoriteRemindersOn) "提醒已开启" else "提醒已关闭"}") }
        } }
        item { SettingsCard("显示与性能") {
            Text("外观", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key,label) -> FilterChip(state.appearance == key, { vm.preference("appearance", key) }, label = { Text(label) }) } }
            Text("配色", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("blue" to "蓝", "green" to "绿", "purple" to "紫", "dynamic" to "系统").forEach { (key,label) -> FilterChip(state.palette == key, { vm.preference("palette", key) }, label = { Text(label) }) } }
            Text("3D 画质与帧率", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("AUTO" to "自动", "HIGH" to "清晰", "SMOOTH" to "流畅", "BATTERY" to "省电").forEach { (key,label) -> FilterChip(state.quality == key, { vm.preference("quality", key) }, label = { Text(label) }) } }
            Text("自动模式保持清晰，持续掉帧或温度较高时才调整；回到后台停止渲染。", style = MaterialTheme.typography.bodySmall)
        } }
        item { SettingsCard("隐私") { ToggleRow("匿名性能数据", "不包含聊天、备注、手机号与账号标识", state.telemetryOn, vm::telemetry) } }
        item { SettingsCard("朋友与代取") { Text("扫描个人二维码添加朋友后，可在「我的洗衣」中发送代取请求。"); FilledTonalButton(onClick = { vm.tab(1) }) { Text("查看朋友与代取") } } }
        if (state.access.optBoolean("adminVisible")) item { SettingsCard("管理") {
            FilledTonalButton(onClick = { admin = true }, modifier = Modifier.fillMaxWidth()) { Text("管理员后台") }
        } }
        item { SettingsCard("关于 WeShirt") {
            Text("WeShirt v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text("本程序的初衷只是为了方便作者自己洗衣服。若有帮到您，您的认可是对我最大的鼓励。如果可以的话，可以请我喝杯咖啡嘛。")
            Text("至今耗费20亿Token和数天用于开发调试，部署于腾讯云CloudBase，这些都是不小的费用。", style = MaterialTheme.typography.bodySmall)
            Text("基于海乐生活公开数据提供服务。设备实际情况请以现场为准。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LaokeQwQ/WeShirt-For-Android"))) }) { Icon(painterResource(R.drawable.ic_github), null); Spacer(Modifier.width(8.dp)); Text("源代码仓库") }
            Image(painterResource(R.drawable.donation_wechat), "微信捐赠二维码", Modifier.fillMaxWidth().heightIn(max = 340.dp))
            Text("Made with 💗 by Laoke", style = MaterialTheme.typography.labelMedium)
        } }
    }
    if (admin && state.access.optBoolean("adminVisible")) com.laoke.weshirt.ui.admin.NativeAdminScreen(state, vm) { admin = false }
    if (favoriteSettings && state.profile != null) FavoritePreferencesDialog(state, vm) { favoriteSettings = false }
    if(avatarMenu) AlertDialog(onDismissRequest = { avatarMenu = false }, title = { Text("头像") }, text = { Column {
        TextButton(onClick = { vm.mutate { vm.api.call("/api/user/dingtalk-avatar") }; avatarMenu = false }) { Text("用钉钉头像") }
        TextButton(onClick = { vm.mutate { vm.api.call("/api/user/profile", "POST", JSONObject().put("avatarUrl", "")) }; avatarMenu = false }) { Text("移除头像，使用昵称首字") }
        TextButton(onClick = { avatarOwner = owner; avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)); avatarMenu = false }) { Text("自定义头像") }
    } }, confirmButton = { TextButton(onClick = { avatarMenu = false }) { Text("取消") } })
    if(logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出当前账号？") }, text = { Text("会清理本机账号数据和洗衣通知。") }, confirmButton = { TextButton(onClick = { vm.logout(); logout = false }) { Text("退出登录") } }, dismissButton = { TextButton(onClick = { logout = false }) { Text("取消") } })
}
@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); content() } } }
@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, change) } }
