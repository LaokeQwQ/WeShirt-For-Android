@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.laoke.weshirt.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@Composable fun ChannelScreen(state: AppState, vm: AppViewModel, login: () -> Unit) {
    val scope = ChatScope(state.endpoint, state.profile?.id, state.channel)
    val draft = remember(scope) { Any() }
    val currentDraft by rememberUpdatedState(draft)
    var input by remember(scope) { mutableStateOf("") }; var reply by remember(scope) { mutableStateOf<Message?>(null) }
    var mentions by remember(scope) { mutableStateOf(setOf<String>()) }; var all by remember(scope) { mutableStateOf(false) }
    var menu by remember(scope) { mutableStateOf<Message?>(null) }; var media by remember(scope) { mutableStateOf<Message?>(null) }
    var attached by remember(scope) { mutableStateOf<String?>(null) }
    var pickerTarget by remember { mutableStateOf<Pair<ChatScope, Any>?>(null) }
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pickerTarget
        pickerTarget = null
        if (uri != null && target != null) {
            if (target.first != scope || target.second !== currentDraft || !target.first.matches(vm.state.value)) {
                vm.message("频道或账号已切换，请重新选择附件")
            } else vm.mutate(null) {
                if (!target.first.matches(vm.state.value) || target.second !== currentDraft) throw CancellationException("Chat changed")
                val pair = withContext(Dispatchers.IO) { prepareMedia(context, uri) }
                if (!target.first.matches(vm.state.value) || target.second !== currentDraft) throw CancellationException("Chat changed")
                val result = vm.api.upload("/api/messages/media?channel=${vm.api.encoded(target.first.channel)}", pair.first, pair.second)
                if (target.first.matches(vm.state.value) && target.second === currentDraft) {
                    attached = result.optJSONObject("media")?.str("id")?.takeIf { it.isNotBlank() }
                        ?: throw ApiException(502, "missing_media_id", "client_" + UUID.randomUUID().toString().replace("-", ""), "附件上传结果异常")
                    vm.message("附件已准备好，点击发送发布")
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(state.channels, key = { it.code }) { channel -> FilterChip(selected = channel.code == state.channel, onClick = { vm.channel(channel.code) }, label = { Text(channel.name) }) } }
        if(state.profile == null) { EmptyPanel("和楼里的朋友聊聊", "登录后可查看公共频道和自己的住宿楼频道。", Icons.Outlined.ChatBubbleOutline, "钉钉登录", login); return }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), reverseLayout = true) {
            if(state.messages.isEmpty()) item { Text("还没有消息，来打个招呼吧。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(40.dp).combinedClickable(onClick = {}, onLongClick = { if(!state.busy && message.mentionRef.isNotBlank()) { mentions = mentions + message.mentionRef; input = (input + "@${message.nick} ").take(500) } }), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { if(message.avatar.isNotBlank()) AsyncImage(ImageRequest.Builder(context).data(message.avatar).memoryCacheKey("avatar|${scope.endpoint}|${scope.profileId}|${message.avatar}").diskCachePolicy(coil.request.CachePolicy.DISABLED).build(), "${message.nick}的头像", Modifier.fillMaxSize()) else Text(message.nick.take(1)) } }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(message.nick, style = MaterialTheme.typography.labelLarge); if(message.building.isNotBlank()) Text(message.building, style = MaterialTheme.typography.labelSmall); if(message.role.isNotBlank() && message.role != "用户") SuggestionChip(onClick = {}, label = { Text(message.role) }, modifier = Modifier.height(22.dp)) }
                        Surface(Modifier.combinedClickable(onClick = { if(message.mediaId.isNotBlank()) media = message }, onLongClick = { menu = message }), shape = RoundedCornerShape(18.dp), color = if(message.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if(message.pinned) Text("置顶", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                if(message.mentioned) Text("有人 @ 你", color = androidx.compose.ui.graphics.Color(0xFFD87717), style = MaterialTheme.typography.labelMedium)
                                if(message.reply.isNotBlank()) Text(message.reply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if(message.text.isNotBlank()) Text(message.text)
                                if(message.mediaId.isNotBlank()) Row { Icon(if(message.mediaKind == "video") Icons.Outlined.PlayCircleOutline else Icons.Outlined.Image, null); Text(if(message.mediaKind == "video") "点击播放视频" else "点击查看图片") }
                            }
                        }
                    }
                }
            }
        }
        if(reply != null || attached != null) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) { Text(if(attached != null) "已添加附件" else "回复 ${reply?.nick}：${reply?.text?.take(20)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); IconButton(onClick = { reply = null; attached = null }) { Icon(Icons.Outlined.Close, "取消引用或附件") } }
        Surface(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pickerTarget = scope to draft; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, enabled = !state.busy && pickerTarget == null) { Icon(Icons.Outlined.Add, "发送图片或30秒内视频") }
                TextField(input, { input = it.take(500) }, enabled = !state.busy, placeholder = { Text("聊点什么 · 长按头像 @") }, modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(24.dp), colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                IconButton(onClick = {
                    val text = input; val ref = reply?.id; val refs = mentions.toList(); val mentionAll = all; val mediaId = attached
                    val sentDraft = draft
                    vm.mutate(null) {
                        if (!scope.matches(vm.state.value) || sentDraft !== currentDraft) throw CancellationException("Chat changed")
                        vm.api.call("/api/messages", "POST", JSONObject().put("channel", scope.channel).put("text", text).put("replyTo", ref).put("mentions", org.json.JSONArray(refs)).put("mentionAll", mentionAll).put("mediaId", mediaId))
                        // Keep the draft after a failed request or if another conversation replaced it.
                        if (scope.matches(vm.state.value) && sentDraft === currentDraft && input == text && reply?.id == ref &&
                            mentions == refs.toSet() && all == mentionAll && attached == mediaId) {
                            input = ""; reply = null; mentions = emptySet(); all = false; attached = null
                        }
                    }
                }, enabled = !state.busy && (input.isNotBlank() || attached != null)) { Icon(Icons.Outlined.Send, "发送", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
    menu?.let { message -> AlertDialog(onDismissRequest = { menu = null }, title = { Text("消息操作") }, text = { Column {
        TextButton(onClick = { reply = message; menu = null }, enabled = !state.busy) { Text("回复") }
        TextButton(onClick = { vm.report(message); menu = null }, enabled = !state.busy) { Text("举报") }
        if(state.channels.find { it.code == state.channel }?.mentionAll == true) {
            TextButton(onClick = { all = true; input = (input + "@全体成员 ").take(500); menu = null }, enabled = !state.busy) { Text("@ 全体成员") }
            TextButton(onClick = { vm.mutate { vm.api.call("/api/messages/${vm.api.encoded(message.id)}/pin", "POST", JSONObject().put("pinned", !message.pinned)) }; menu = null }, enabled = !state.busy) { Text(if(message.pinned) "取消置顶" else "置顶消息") }
        }
        if(message.mine) TextButton(onClick = { vm.mutate { vm.api.call("/api/messages/${vm.api.encoded(message.id)}", "DELETE") }; menu = null }, enabled = !state.busy) { Text("删除消息") }
    } }, confirmButton = { TextButton(onClick = { menu = null }) { Text("取消") } }) }
    media?.let { message -> key(scope.endpoint, scope.profileId, message.mediaId) { MediaDialog(message, scope, vm) { media = null } } }
}

private data class ChatScope(val endpoint: String, val profileId: String?, val channel: String) {
    fun matches(state: AppState) = profileId != null && endpoint == state.endpoint && profileId == state.profile?.id && channel == state.channel
}
private fun prepareMedia(context: android.content.Context, uri: Uri): Pair<ByteArray, String> {
    try { return decodeMedia(context, uri) }
    catch(problem: Exception) {
        if(problem is CancellationException || problem is ApiException) throw problem
        val allowed = setOf("无法读取图片", "图片过大，请裁剪后重试", "请使用 JPG/PNG 图片或 MP4/MOV 视频", "无法读取视频时长", "视频最长30秒", "无法读取视频", "视频最大8MB，请压缩后重试")
        val message = problem.message?.takeIf { it in allowed } ?: "无法读取这个附件，请重新选择"
        throw ApiException(422, "invalid_media", "client_" + UUID.randomUUID().toString().replace("-", ""), message)
    }
}
private fun decodeMedia(context: android.content.Context, uri: Uri): Pair<ByteArray, String> {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if(mime.startsWith("image/")) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片" }
        var sample = 1; while(options.outWidth / sample > 1600 || options.outHeight / sample > 1600) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("无法读取图片")
        val output = ByteArrayOutputStream(); var quality = 85
        do { output.reset(); bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output); quality -= 10 } while(output.size() > 490_000 && quality > 20)
        bitmap.recycle(); require(output.size() <= 500_000) { "图片过大，请裁剪后重试" }; return output.toByteArray() to "image/jpeg"
    }
    require(mime in listOf("video/mp4", "video/quicktime")) { "请使用 JPG/PNG 图片或 MP4/MOV 视频" }
    MediaMetadataRetriever().use { r -> r.setDataSource(context, uri); val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("无法读取视频时长"); require(duration in 1..30_000) { "视频最长30秒" } }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() <= 8 * 1024 * 1024) {
            val count = input.read(buffer, 0, minOf(buffer.size, 8 * 1024 * 1024 + 1 - output.size()))
            if(count < 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("无法读取视频")
    require(bytes.size <= 8 * 1024 * 1024) { "视频最大8MB，请压缩后重试" }; return bytes to mime
}
@Composable private fun MediaDialog(message: Message, scope: ChatScope, vm: AppViewModel, close: () -> Unit) {
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val failure = remember { "媒体暂时无法显示\n状态码 502 · 事件 client_" + UUID.randomUUID().toString().replace("-", "") }
    LaunchedEffect(message.mediaId, scope) {
        try {
            if (!scope.matches(vm.state.value)) throw CancellationException("Chat changed")
            val downloaded = vm.api.downloadMedia(message.mediaId, if(message.mediaKind == "video") 8L * 1024 * 1024 else 1024L * 1024)
            if (!scope.matches(vm.state.value)) throw CancellationException("Chat changed")
            file = downloaded
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: Exception) { error = if(problem is ApiException) problem.message else failure }
    }
    DisposableEffect(owner, player) {
        val video = player
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) video?.pause()
            if(event == Lifecycle.Event.ON_DESTROY) video?.stopPlayback()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); video?.stopPlayback() }
    }
    DisposableEffect(file) {
        val downloaded = file
        onDispose { downloaded?.delete() }
    }
    AlertDialog(onDismissRequest = close, title = { Text(if(message.mediaKind == "video") "频道视频" else "频道图片") }, text = {
        val local = file
        if(error != null) Text(error!!)
        else if(local == null) CircularProgressIndicator()
        else if(message.mediaKind == "video") key(local.absolutePath) {
            AndroidView(factory = { c -> VideoView(c).apply {
                player = this
                // Playback receives a private local file, never an authenticated network URL.
                setVideoURI(Uri.fromFile(local))
                setMediaController(MediaController(c).also { it.setAnchorView(this) })
                setOnPreparedListener { if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start() }
                setOnErrorListener { _, _, _ -> error = failure; true }
            } }, modifier = Modifier.fillMaxWidth().height(260.dp), onRelease = { released ->
                released.stopPlayback()
                if(player === released) player = null
            })
        } else {
            val request = remember(local, scope.endpoint, scope.profileId, message.mediaId) {
                ImageRequest.Builder(context).data(local)
                    .memoryCacheKey("media|${scope.endpoint}|${scope.profileId}|${message.mediaId}")
                    .diskCachePolicy(coil.request.CachePolicy.DISABLED).build()
            }
            AsyncImage(request, "频道图片", Modifier.fillMaxWidth().heightIn(max = 400.dp), onError = { error = failure })
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}
