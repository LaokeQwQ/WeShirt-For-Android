package com.laoke.weshirt.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Picker access is scoped to selected content; no all-files or whole-gallery permission. */
@Composable
fun NativeAttachmentActions(
    enabled: Boolean,
    scopeKey: String,
    onMedia: (Uri, cleanup: () -> Unit) -> Unit,
    onError: (String) -> Unit,
    imagesOnly: Boolean = false,
    onModalVisibility: (Boolean) -> Unit = {},
    content: (@Composable (AttachmentActions) -> Unit)? = null,
) {
    val context = LocalContext.current
    val currentScope by rememberUpdatedState(scopeKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentMedia by rememberUpdatedState(onMedia)
    val currentError by rememberUpdatedState(onError)
    var menu by remember { mutableStateOf(false) }
    val modalVisibility by rememberUpdatedState(onModalVisibility)
    var pendingScope by rememberSaveable { mutableStateOf<String?>(null) }
    var captureName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVideo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(menu, pendingScope) { modalVisibility(menu || pendingScope != null) }
    DisposableEffect(Unit) { onDispose { modalVisibility(false) } }
    LaunchedEffect(scopeKey) { menu = false }
    fun failure(message: String) { currentError(clientFailure(IllegalArgumentException(),message)) }
    val directory = remember(context) { File(context.cacheDir, "media-captures").apply {
        mkdirs()
        // Abandoned temporary captures are never exported or retained as a gallery.
        listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
    } }
    fun deliver(uri: Uri?, captured: Boolean) {
        val expected = pendingScope
        pendingScope = null
        val name = captureName
        captureName = null
        val cleanup = { if (captured && name != null) File(directory, name).delete(); Unit }
        if (uri == null) { cleanup(); return }
        if (expected == null || expected != currentScope) {
            cleanup(); failure("账号或页面已切换，请重新选择附件"); return
        }
        if (!currentEnabled) { cleanup(); failure("正在处理其他操作，请稍后重新选择附件"); return }
        currentMedia(uri, cleanup)
    }
    val camera = rememberLauncherForActivityResult(ShortCaptureContract()) { success ->
        val file = captureName?.let { File(directory, it) }
        val uri = if (success && file?.isFile == true && file.length() > 0) FileProvider.getUriForFile(context, context.packageName + ".media", file) else null
        deliver(uri, true)
    }
    fun launchCamera() {
        if (pendingScope != currentScope || !currentEnabled) { pendingScope = null; return }
        try {
            val name = UUID.randomUUID().toString() + if (pendingVideo) ".mp4" else ".jpg"
            captureName = name
            val file = File(directory, name).apply { createNewFile() }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".media", file)
            camera.launch(CaptureRequest(uri, pendingVideo))
        } catch (_: RuntimeException) {
            captureName?.let { File(directory, it).delete() }; captureName = null; pendingScope = null
            failure("当前设备无法打开相机，可从相册或文件选择")
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val required = listOf(Manifest.permission.CAMERA) + if (pendingVideo) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
        if (required.all { result[it] == true || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) launchCamera()
        else { pendingScope = null; failure("未获得所需权限，仍可从相册或文件选择附件") }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { deliver(it, false) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { deliver(it, false) }
    fun capture(video: Boolean) {
        if (!currentEnabled || pendingScope != null) return
        menu = false; pendingScope = scopeKey; pendingVideo = video
        val required = listOf(Manifest.permission.CAMERA) + if (video) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
        if (required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) launchCamera()
        else permissions.launch(required.toTypedArray())
    }
    val available = enabled && pendingScope == null
    val actions = AttachmentActions(menu, available,
        toggle = { menu = !menu }, close = { menu = false },
        gallery = { if (currentEnabled && pendingScope == null) { menu = false; pendingScope = scopeKey; gallery.launch(PickVisualMediaRequest(if (imagesOnly) ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.ImageAndVideo)) } },
        photo = { capture(false) }, video = { capture(true) },
        file = { if (currentEnabled && pendingScope == null) { menu = false; pendingScope = scopeKey; document.launch(if (imagesOnly) arrayOf("image/*") else arrayOf("image/*", "video/mp4", "video/quicktime")) } })
    if (content != null) content(actions)
    else {
        IconButton(onClick = actions.toggle, enabled = available) { Icon(Icons.Outlined.Add, "添加图片、视频或拍摄") }
        if (menu) AlertDialog(onDismissRequest = actions.close, title = { Text("添加附件") }, text = {
            Column {
                TextButton(onClick = actions.gallery, enabled = available) { Text("从相册选择") }
                TextButton(onClick = actions.photo, enabled = available) { Text("相机拍照") }
                if (!imagesOnly) TextButton(onClick = actions.video, enabled = available) { Text("录制视频（最多30秒）") }
                TextButton(onClick = actions.file, enabled = available) { Text("从文件选择") }
            }
        }, confirmButton = { TextButton(onClick = actions.close) { Text("取消") } })
    }
}

/** Uses the same permission-scoped launchers in dialogs and the channel's inline composer panel. */
data class AttachmentActions(val expanded: Boolean, val enabled: Boolean, val toggle: () -> Unit, val close: () -> Unit,
                             val gallery: () -> Unit, val photo: () -> Unit, val video: () -> Unit, val file: () -> Unit)

@Composable
internal fun AttachmentComposerPanel(actions: AttachmentActions) {
    Surface(Modifier.fillMaxWidth().testTag("channel-attachment-panel"), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("添加内容", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = actions.close, modifier = Modifier.size(40.dp).testTag("channel-attachments-close")) { Icon(Icons.Outlined.Close, "收起附件面板") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    listOf(Triple("图库", Icons.Outlined.PhotoLibrary, actions.gallery), Triple("拍照", Icons.Outlined.PhotoCamera, actions.photo)),
                    listOf(Triple("视频 · 30秒内", Icons.Outlined.Videocam, actions.video), Triple("文件", Icons.Outlined.InsertDriveFile, actions.file))
                ).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (title, icon, click) ->
                            FilledTonalButton(onClick = click, enabled = actions.enabled, modifier = Modifier.weight(1f).heightIn(min = 64.dp), shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(10.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(icon, null, Modifier.size(24.dp)); Text(title, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class CaptureRequest(val uri: Uri, val video: Boolean)
private class ShortCaptureContract : ActivityResultContract<CaptureRequest, Boolean>() {
    override fun createIntent(context: Context, input: CaptureRequest): Intent = Intent(if (input.video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, input.uri)
        .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
        .putExtra(MediaStore.EXTRA_SIZE_LIMIT, 8L * 1024 * 1024)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("capture", input.uri) }
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}
