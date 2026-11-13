package com.laoke.weshirt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.laoke.weshirt.data.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.io.File

/** Authenticated bytes stay in the API's private temporary directory, never a public image URL/cache. */
@Composable
internal fun PrivateMessageImage(messageId: String, mediaId: String, origin: String, account: String,
                                 api: ApiClient, visible: Boolean, current: () -> Boolean, open: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val active by rememberUpdatedState(current)
    var file by remember(origin, account, mediaId) { mutableStateOf<File?>(null) }
    var failed by remember(origin, account, mediaId) { mutableStateOf<String?>(null) }
    var decoded by remember(origin, account, mediaId) { mutableStateOf(false) }
    var retry by remember(origin, account, mediaId) { mutableIntStateOf(0) }
    LaunchedEffect(origin, account, mediaId, visible, owner, retry) {
        if (!visible) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var downloaded: File? = null
            try {
                if (!active()) return@repeatOnLifecycle
                failed = null
                decoded = false
                downloaded = api.downloadMedia(mediaId, 4L * 1024 * 1024)
                if (!active()) throw CancellationException("Conversation changed")
                file = downloaded
                awaitCancellation()
            } catch (cancel: CancellationException) { throw cancel }
            catch (problem: Exception) { failed = clientFailure(problem, "图片暂未加载") }
            finally { file = null; downloaded?.delete() }
        }
    }
    Surface(Modifier.fillMaxWidth().heightIn(min = 144.dp, max = 280.dp).clip(RoundedCornerShape(14.dp)),
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        val local = file
        if (local != null && failed == null) {
            val request = remember(local) {
                ImageRequest.Builder(context).data(local).size(960).memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED).build()
            }
            AsyncImage(request, "频道图片", Modifier.fillMaxWidth().heightIn(min = 144.dp, max = 280.dp)
                .clickable(onClick = open).testTag(if (decoded) "channel-image:$messageId" else "channel-image-loading:$messageId"), contentScale = ContentScale.Fit,
                onError = { failed = clientFailure(IllegalArgumentException(), "图片格式暂不支持") },
                onSuccess = { failed = null; decoded = true })
        } else Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (failed != null) {
                Icon(Icons.Outlined.BrokenImage, null)
                Text(failed.orEmpty(), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text("重新加载图片") }
            } else {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("正在加载图片", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
