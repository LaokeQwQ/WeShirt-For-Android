package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.platform.update.AppUpdateViewModel
import kotlin.math.roundToInt

@Composable
internal fun AppUpdatePanel(updates: AppUpdateViewModel, install: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("当前版本 v${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.bodyMedium)
        val release = state.release
        if (release != null) {
            Text("可更新至 v${release.versionName}（构建 ${release.versionCode}）",
                style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val progress = state.progress
        when {
            progress != null -> {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("正在下载并校验更新 ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            state.ready -> Button(onClick = install, enabled = !state.checking) { Text("安装更新") }
            release != null && !state.checking -> Button(onClick = updates::download) { Text("下载更新") }
        }
        OutlinedButton(onClick = { updates.check() }, enabled = !state.checking && progress == null) {
            Text(if (state.checking) "正在检查更新" else "检查更新")
        }
    }
}
