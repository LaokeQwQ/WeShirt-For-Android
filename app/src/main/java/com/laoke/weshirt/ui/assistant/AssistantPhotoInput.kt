package com.laoke.weshirt.ui.assistant

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.ui.NativeAttachmentActions
import com.laoke.weshirt.ui.user.UserFeatureSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal data class AssistantPhoto(val preview: Bitmap, val dataUrl: String)

private suspend fun readAssistantPhoto(resolver: ContentResolver, uri: Uri): AssistantPhoto = withContext(Dispatchers.IO) {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0) { "图片无法读取" }
        val scale = minOf(1.0, 1024.0 / maxOf(width, height))
        decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try {
        val output = ByteArrayOutputStream()
        for (quality in listOf(84, 68, 52)) {
            output.reset()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "图片无法转换" }
            if (output.size() <= 960_000) {
                val url = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                return@withContext AssistantPhoto(bitmap, url)
            }
        }
        throw IllegalArgumentException("图片过大，请选择另一张图片")
    } catch (problem: Exception) {
        bitmap.recycle()
        throw problem
    }
}

@Composable
internal fun AssistantPhotoInput(session: UserFeatureSession, scopeKey: String, enabled: Boolean,
                                 onPhoto: (AssistantPhoto) -> Unit) {
    val context = LocalContext.current
    NativeAttachmentActions(enabled, scopeKey, imagesOnly = true, onError = { session.error = it },
        onMedia = { uri, cleanup ->
            if (!session.run {
                try {
                    val photo = readAssistantPhoto(context.contentResolver, uri)
                    current()
                    onPhoto(photo)
                } finally {
                    cleanup()
                }
            }) cleanup()
        }) { actions ->
        Box {
            IconButton(onClick = actions.toggle, enabled = actions.enabled, modifier = Modifier.testTag("assistant-photo-input")) {
                Icon(Icons.Outlined.AddPhotoAlternate, "添加照片")
            }
            DropdownMenu(expanded = actions.expanded, onDismissRequest = actions.close) {
                DropdownMenuItem(text = { Text("相机拍照") }, onClick = actions.photo, enabled = actions.enabled)
                DropdownMenuItem(text = { Text("从相册选择") }, onClick = actions.gallery, enabled = actions.enabled)
            }
        }
    }
}

@Composable
internal fun AssistantPhotoPreview(photo: AssistantPhoto, enabled: Boolean, onRemove: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("assistant-photo-preview")) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(photo.preview.asImageBitmap(), "待发送照片", Modifier.size(72.dp), contentScale = ContentScale.Fit)
            Text("已添加照片", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag("assistant-photo-remove")) {
                Icon(Icons.Outlined.Close, "移除照片")
            }
        }
    }
}
