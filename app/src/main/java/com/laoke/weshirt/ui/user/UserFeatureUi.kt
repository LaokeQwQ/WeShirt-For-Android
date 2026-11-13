package com.laoke.weshirt.ui.user

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.ui.clientFailure
import kotlinx.coroutines.*
import org.json.JSONObject

/** A dialog owns its requests; dismissing or changing accounts cancels all pending work. */
internal class UserFeatureSession(private val vm: AppViewModel, private val scope: CoroutineScope,
                                  private val endpoint: String, private val userId: String?) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    private var active = true
    private var job: Job? = null
    fun current() {
        if (!active || userId == null || !vm.state.value.authReady || vm.state.value.endpoint != endpoint || vm.api.baseUrl != endpoint || vm.state.value.profile?.id != userId) throw CancellationException("Account or service changed")
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true): JSONObject {
        current()
        val response = vm.api.call(path, method, body, auth)
        current()
        currentCoroutineContext().ensureActive()
        return response
    }
    fun run(refresh: Boolean = false, silent: Boolean = false, block: suspend UserFeatureSession.() -> Unit): Boolean {
        if (!active || busy) return false
        busy = true
        if (!silent) error = null
        job = scope.launch {
            try { current(); block(); current(); if (refresh) vm.refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (active) error = clientFailure(failure, failure.message ?: "操作未完成，请重试") }
            finally { if (active) busy = false }
        }
        return true
    }
    fun dispose() { active = false; job?.cancel() }
}

@Composable internal fun rememberUserFeatureSession(state: AppState, vm: AppViewModel): UserFeatureSession {
    val scope = rememberCoroutineScope()
    val session = remember(state.endpoint, state.profile?.id, vm) { UserFeatureSession(vm, scope, state.endpoint, state.profile?.id) }
    DisposableEffect(session) { onDispose { session.dispose() } }
    return session
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun UserFeatureDialog(title: String, close: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
                                         content: @Composable (PaddingValues) -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = actions) }, content = content)
        }
    }
}

@Composable internal fun FeatureError(session: UserFeatureSession) {
    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable internal fun FeatureAvatar(nick: String, url: String, state: AppState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(state.endpoint, state.profile?.id, url) { mutableStateOf(false) }
    val safe = url.startsWith("data:image/") || runCatching { java.net.URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    Surface(modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (url.isNotBlank() && safe && !failed) AsyncImage(
                ImageRequest.Builder(context).data(url).memoryCacheKey("social-avatar|${state.endpoint}|${state.profile?.id}|$url").diskCachePolicy(CachePolicy.DISABLED).build(),
                "${nick}的头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true }
            ) else Text(nick.take(1).ifBlank { "友" }, style = MaterialTheme.typography.titleMedium)
        }
    }
}

internal fun copyFeatureText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("WeShirt", text))
}
internal fun shareFeatureText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享 WeShirt"))
}

@Composable internal fun ShareCodeDialog(title: String, url: String, hint: String, close: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, url) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val size = 600
                val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                    setPixels(pixels, 0, size, 0, 0, size, size)
                }
            }.getOrNull()
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            bitmap?.let { Image(it.asImageBitmap(), title, Modifier.fillMaxWidth().aspectRatio(1f)) }
            if (bitmap == null) Text("二维码正在准备，亦可直接复制链接分享。", style = MaterialTheme.typography.bodySmall)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { copyFeatureText(context, url) }) { Text("复制链接") }
        }
    }, confirmButton = { TextButton(onClick = { shareFeatureText(context, url) }) { Text("分享") } }, dismissButton = { TextButton(onClick = close) { Text("关闭") } })
}
