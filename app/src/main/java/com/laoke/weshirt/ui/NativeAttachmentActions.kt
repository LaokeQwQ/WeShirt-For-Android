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
import androidx.compose.material.icons.outlined.Add
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
) {
    val context = LocalContext.current
    val currentScope by rememberUpdatedState(scopeKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentMedia by rememberUpdatedState(onMedia)
    val currentError by rememberUpdatedState(onError)
    var menu by remember { mutableStateOf(false) }
    var pendingScope by rememberSaveable { mutableStateOf<String?>(null) }
    var captureName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVideo by rememberSaveable { mutableStateOf(false) }
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
    IconButton(onClick = { menu = true }, enabled = enabled && pendingScope == null) { Icon(Icons.Outlined.Add, "添加图片、视频或拍摄") }
    if (menu) AlertDialog(onDismissRequest = { menu = false }, title = { Text("添加附件") }, text = {
        androidx.compose.foundation.layout.Column {
            TextButton(onClick = { if (currentEnabled && pendingScope == null) { menu = false; pendingScope = scopeKey; gallery.launch(PickVisualMediaRequest(if (imagesOnly) ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.ImageAndVideo)) } },enabled=enabled && pendingScope==null) { Text("从相册选择") }
            TextButton(onClick = { capture(false) },enabled=enabled && pendingScope==null) { Text("相机拍照") }
            if (!imagesOnly) TextButton(onClick = { capture(true) },enabled=enabled && pendingScope==null) { Text("录制视频（最多30秒）") }
            TextButton(onClick = { if (currentEnabled && pendingScope == null) { menu = false; pendingScope = scopeKey; document.launch(if (imagesOnly) arrayOf("image/*") else arrayOf("image/*", "video/mp4", "video/quicktime")) } },enabled=enabled && pendingScope==null) { Text("从文件选择") }
        }
    }, confirmButton = { TextButton(onClick = { menu = false }) { Text("取消") } })
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
