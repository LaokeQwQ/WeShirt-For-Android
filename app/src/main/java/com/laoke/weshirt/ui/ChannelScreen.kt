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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@Composable fun ChannelScreen(state: AppState, vm: AppViewModel, login: () -> Unit) {
    var directory by remember(state.endpoint, state.profile?.id) { mutableStateOf(state.discussionMachine.isBlank()) }
    LaunchedEffect(state.endpoint, state.profile?.id, directory) { if (state.profile != null && directory) vm.refreshChannels() }
    LaunchedEffect(state.discussionMachine) { if (state.discussionMachine.isNotBlank()) directory = false }
    var connected by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(state.endpoint, state.profile?.id, state.channel, directory, owner) {
        if (state.profile != null && !directory) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            coroutineScope {
                launch {
                    channelHttpFallback(current = {
                        val current = vm.state.value
                        current.tab == 2 && current.channel == state.channel && current.endpoint == state.endpoint && current.profile?.id == state.profile.id
                    }, connected = { connected }, refresh = { vm.refreshMessages() })
                }
                ChannelRealtime(vm.api).run(state.channel, { vm.refreshMessages() }, { connected = it; vm.channelConnection(it) })
            }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.channelConnection(false) } }
    if (state.profile == null) { EmptyPanel("和楼里的朋友聊聊", "登录后可查看公共频道和自己的住宿楼频道。", Icons.Outlined.ChatBubbleOutline, "钉钉登录", login); return }
    if (directory) {
        ChannelDirectory(state.channels, state.profile.building, "${state.endpoint}|${state.profile.id}",
            state.messageNotices.filter { it.str("readAt").isBlank() }.map { it.str("channel") }.toSet(),
            onSelect = { vm.channel(it.code); directory = false }, retry = vm::refreshChannels)
        return
    }
    val scope = ChatScope(state.endpoint, state.profile?.id, state.channel, state.discussionMachine)
    val messageList = key(scope) { rememberLazyListState() }
    val currentMessages by rememberUpdatedState(state.messages)
    val acknowledged = remember(scope) { mutableSetOf<String>() }
    val draft = remember(scope) { Any() }
    val currentDraft by rememberUpdatedState(draft)
    var input by remember(scope) { mutableStateOf("") }; var reply by remember(scope) { mutableStateOf<Message?>(null) }
    var mentions by remember(scope) { mutableStateOf(setOf<String>()) }; var all by remember(scope) { mutableStateOf(false) }
    var menu by remember(scope) { mutableStateOf<Message?>(null) }; var media by remember(scope) { mutableStateOf<Message?>(null) }
    var attached by remember(scope) { mutableStateOf<String?>(null) }
    var announcement by remember(scope) { mutableStateOf(false) }
    var channelAvatar by remember(scope) { mutableStateOf(false) }
    var attachmentModal by remember(scope) { mutableStateOf(false) }
    var avatarModal by remember(scope) { mutableStateOf(false) }
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val minimumVisiblePixels = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.roundToPx() }
    LaunchedEffect(scope, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                if (!windowInfo.isWindowFocused || menu != null || media != null || attachmentModal || avatarModal) emptyList()
                else {
                    val layout = messageList.layoutInfo
                    visibleIncomingMessageIds(currentMessages, layout.visibleItemsInfo.filter {
                        isMessageVisible(it.offset, it.size, layout.viewportStartOffset, layout.viewportEndOffset, minimumVisiblePixels)
                    }.map { it.key })
                }
            }
                .distinctUntilChanged().collectLatest { visible ->
                    // Do not acknowledge while a fling is exposing an item for a single frame.
                    delay(350)
                    val pending = visible.filterNot { it in acknowledged }
                    if (pending.isNotEmpty() && scope.matches(vm.state.value) && vm.state.value.tab == 2) {
                        var attempts = 0
                        while (attempts++ < 3 && scope.matches(vm.state.value) && vm.state.value.tab == 2) {
                            if (vm.readVisibleMessages(scope.channel, pending)) { acknowledged.addAll(pending); break }
                            delay(1_500)
                        }
                    }
                }
        }
    }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { directory = true; vm.channelConnection(false); vm.refreshChannels() }) { Text("所有频道") }
            Text(if (connected) "实时连接" else "定期同步", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            if (state.channels.find { it.code == state.channel }?.canManageAvatar == true) TextButton(onClick = { channelAvatar = !channelAvatar }) { Text("频道头像") }
        }
        if (channelAvatar) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeAttachmentActions(!state.busy, "avatar|${scope.endpoint}|${scope.profileId}|${scope.channel}", onMedia = { uri, cleanup ->
                vm.mutate("频道头像已更新", wait = true) { try {
                    if (!scope.matches(vm.state.value)) throw CancellationException("Chat changed")
                    val image = withContext(Dispatchers.IO) { compressPhoto(context, uri, 64 * 1024, 256) }
                    if (!scope.matches(vm.state.value)) throw CancellationException("Chat changed")
                    vm.api.upload("/api/messages/channels/${vm.api.encoded(scope.channel)}/avatar", image, "image/jpeg")
                    channelAvatar = false
                } finally { cleanup() } }
            }, onError = vm::message, imagesOnly = true, onModalVisibility = { avatarModal = it })
            Text("选择或拍摄频道头像", style = MaterialTheme.typography.bodySmall)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(state.channels, key = { it.code }) { channel -> FilterChip(selected = channel.code == state.channel, onClick = { vm.channel(channel.code) }, label = { Text(channel.name) }) } }
        if (state.discussionMachine.isNotBlank()) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("仅看 ${state.machines.find { it.id == state.discussionMachine }?.name ?: "这台机器"} 的留言", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { vm.channel(state.channel) }) { Text("查看全频道") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("channel-message-list"), state = messageList, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), reverseLayout = true) {
            if(state.messages.isEmpty()) item { Text("还没有消息，来打个招呼吧。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth().testTag("channel-message:${message.id}"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(40.dp).combinedClickable(onClick = {}, onLongClick = { if(!state.busy && message.mentionRef.isNotBlank()) { mentions = mentions + message.mentionRef; input = (input + "@${message.nick} ").take(200) } }), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { if(message.avatar.isNotBlank()) AsyncImage(ImageRequest.Builder(context).data(message.avatar).memoryCacheKey("avatar|${scope.endpoint}|${scope.profileId}|${message.avatar}").diskCachePolicy(coil.request.CachePolicy.DISABLED).build(), "${message.nick}的头像", Modifier.fillMaxSize()) else Text(message.nick.take(1)) } }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(message.nick, style = MaterialTheme.typography.labelLarge)
                            if (message.building.isNotBlank()) Text(message.building, style = MaterialTheme.typography.labelSmall)
                            if (message.role.isNotBlank() && message.role != "用户") Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(message.role, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Surface(Modifier.combinedClickable(onClick = { if(message.mediaId.isNotBlank()) media = message }, onLongClick = { menu = message }), shape = RoundedCornerShape(18.dp), color = if(message.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if(message.pinned) Text("置顶", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                if(message.announcement) Text("频道公告", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                if(message.mentioned) Text("有人 @ 你", color = androidx.compose.ui.graphics.Color(0xFFD87717), style = MaterialTheme.typography.labelMedium)
                                if(message.reply.isNotBlank()) Text(message.reply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if(message.text.isNotBlank()) Text(message.text)
                                if (message.mediaId.isNotBlank()) {
                                    if (message.mediaKind == "image") {
                                        val visible by remember(messageList, message.id) { derivedStateOf { messageList.layoutInfo.visibleItemsInfo.any { it.key == message.id } } }
                                        PrivateMessageImage(message.id, message.mediaId, scope.endpoint, scope.profileId.orEmpty(), vm.api, visible,
                                            current = { scope.matches(vm.state.value) }, open = { media = message })
                                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Outlined.PlayCircleOutline, null); Text("播放视频")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.messagesHasMore) item { TextButton(onClick = { vm.moreMessages() }, enabled = !state.busy) { Text("加载更早的消息") } }
        }
        if (state.messageNotices.any { it.str("channel") == state.channel && it.str("readAt").isBlank() }) TextButton(onClick = { vm.readChannelNotices() }, enabled = !state.busy) { Text("有人 @ 你 · 标为已读", color = androidx.compose.ui.graphics.Color(0xFFD87717)) }
        if (state.channels.find { it.code == state.channel }?.canAnnounce == true) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(announcement, { announcement = it }, enabled = !state.busy); Text("作为频道公告发送", style = MaterialTheme.typography.bodySmall) }
        if(reply != null || attached != null) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) { Text(if(attached != null) "已添加附件" else "回复 ${reply?.nick}：${reply?.text?.take(20)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); IconButton(onClick = { reply = null; attached = null }) { Icon(Icons.Outlined.Close, "取消引用或附件") } }
        NativeAttachmentActions(!state.busy, "${scope.endpoint}|${scope.profileId}|${scope.channel}|${scope.machineId}", onMedia = { uri, cleanup ->
                    val target = scope
                    val sentDraft = draft
                    vm.mutate(null, wait = true) {
                        try {
                            if (!target.matches(vm.state.value) || sentDraft !== currentDraft) throw CancellationException("Chat changed")
                            val pair = withContext(Dispatchers.IO) { prepareMedia(context, uri) }
                            if (!target.matches(vm.state.value) || sentDraft !== currentDraft) throw CancellationException("Chat changed")
                            val result = vm.api.upload("/api/messages/media?channel=${vm.api.encoded(target.channel)}", pair.first, pair.second)
                            if (target.matches(vm.state.value) && sentDraft === currentDraft) {
                                attached = result.optJSONObject("media")?.str("id")?.takeIf { it.isNotBlank() }
                                    ?: throw ApiException(502, "missing_media_id", "client_" + UUID.randomUUID().toString().replace("-", ""), "附件上传结果异常")
                                vm.message("附件已准备好，点击发送发布")
                            }
                        } finally { cleanup() }
                    }
                }, onError = vm::message, onModalVisibility = { attachmentModal = it }, content = { actions ->
            Column(Modifier.fillMaxWidth().imePadding()) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = actions.toggle, enabled = actions.enabled, modifier = Modifier.testTag("channel-attachments-toggle")) {
                            Icon(if (actions.expanded) Icons.Outlined.Close else Icons.Outlined.Add, "添加图片、视频或拍摄")
                        }
                TextField(input, { input = it.take(200) }, enabled = !state.busy, placeholder = { Text("聊点什么 · 长按头像 @") }, modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(24.dp), colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                IconButton(onClick = {
                    val text = input; val ref = reply?.id; val refs = mentions.toList(); val mentionAll = all; val mediaId = attached; val asAnnouncement = announcement
                    val sentDraft = draft
                    vm.mutate(null) {
                        if (!scope.matches(vm.state.value) || sentDraft !== currentDraft) throw CancellationException("Chat changed")
                        vm.api.call("/api/messages", "POST", JSONObject().put("channel", scope.channel).put("text", text).put("replyTo", ref).put("mentions", org.json.JSONArray(refs)).put("mentionAll", mentionAll).put("mediaId", mediaId).put("type", if (asAnnouncement) "announcement" else "message").also { if (scope.machineId.isNotBlank()) it.put("machineId", scope.machineId) })
                        if (scope.matches(vm.state.value)) vm.refreshMessages()
                        // Keep the draft after a failed request or if another conversation replaced it.
                        if (scope.matches(vm.state.value) && sentDraft === currentDraft && input == text && reply?.id == ref &&
                            mentions == refs.toSet() && all == mentionAll && attached == mediaId && announcement == asAnnouncement) {
                            input = ""; reply = null; mentions = emptySet(); all = false; attached = null
                        }
                    }
                }, enabled = !state.busy && (input.isNotBlank() || attached != null)) { Icon(Icons.Outlined.Send, "发送", tint = MaterialTheme.colorScheme.primary) }
            }
        }
                if (actions.expanded) AttachmentComposerPanel(actions)
            }
        })
    }
    menu?.let { message -> AlertDialog(onDismissRequest = { menu = null }, title = { Text("消息操作") }, text = { Column {
        TextButton(onClick = { reply = message; menu = null }, enabled = !state.busy) { Text("回复") }
        TextButton(onClick = { vm.mutate("举报已提交管理员") {
            if (!scope.matches(vm.state.value)) throw CancellationException("Chat changed")
            vm.api.call("/api/messages/${vm.api.encoded(message.id)}/report","POST",JSONObject().put("reason","other").put("detail","用户通过原生客户端举报"))
        }; menu = null }, enabled = !state.busy) { Text("举报") }
        if(state.channels.find { it.code == state.channel }?.mentionAll == true) {
            TextButton(onClick = { all = true; input = (input + "@全体成员 ").take(200); menu = null }, enabled = !state.busy) { Text("@ 全体成员") }
        }
        if(message.canPin) {
            TextButton(onClick = { vm.mutate { if (!scope.matches(vm.state.value)) throw CancellationException("Chat changed"); vm.api.call("/api/messages/${vm.api.encoded(message.id)}/pin", "POST", JSONObject().put("pinned", !message.pinned)); if (scope.matches(vm.state.value)) vm.refreshMessages() }; menu = null }, enabled = !state.busy) { Text(if(message.pinned) "取消置顶" else "置顶消息") }
        }
        if(message.canDelete) TextButton(onClick = { vm.mutate { if (!scope.matches(vm.state.value)) throw CancellationException("Chat changed"); vm.api.call("/api/messages/${vm.api.encoded(message.id)}", "DELETE"); if (scope.matches(vm.state.value)) vm.refreshMessages() }; menu = null }, enabled = !state.busy) { Text("删除消息") }
    } }, confirmButton = { TextButton(onClick = { menu = null }) { Text("取消") } }) }
    media?.let { message -> key(scope.endpoint, scope.profileId, message.mediaId) { MediaDialog(message, scope, vm) { media = null } } }
}

private data class ChatScope(val endpoint: String, val profileId: String?, val channel: String, val machineId: String = "") {
    fun matches(state: AppState) = profileId != null && endpoint == state.endpoint && profileId == state.profile?.id && channel == state.channel && machineId == state.discussionMachine
}
internal fun prepareMedia(context: android.content.Context, uri: Uri): Pair<ByteArray, String> {
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
        var bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("无法读取图片")
        val orientation = runCatching { context.contentResolver.openInputStream(uri).use { android.media.ExifInterface(requireNotNull(it)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
        val matrix = android.graphics.Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
        } }
        if (orientation in 2..8) { val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true); if (oriented !== bitmap) bitmap.recycle(); bitmap = oriented }
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
            val downloaded = vm.api.downloadMedia(message.mediaId, if(message.mediaKind == "video") 8L * 1024 * 1024 else 4L * 1024 * 1024)
            if (!scope.matches(vm.state.value)) { downloaded.delete(); throw CancellationException("Chat changed") }
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
