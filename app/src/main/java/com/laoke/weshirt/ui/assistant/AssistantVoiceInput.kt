package com.laoke.weshirt.ui.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.laoke.weshirt.ui.user.UserFeatureSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE = 16_000
private const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * 30

internal fun speechWav(pcm: ByteArray, size: Int): ByteArray {
    require(size in 2..minOf(pcm.size, MAX_PCM_BYTES) && size % 2 == 0)
    return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + size)
        .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        .putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
        .putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII))
        .putInt(size).put(pcm, 0, size).array()
}

@android.annotation.SuppressLint("MissingPermission")
private suspend fun recordSpeech(stopped: AtomicBoolean): ByteArray? = withContext(Dispatchers.IO) {
    val chunk = 3200
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minBuffer > 0) { "此设备暂不支持麦克风录音" }
    val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(chunk * 2, minBuffer))
    val pcm = ByteArray(MAX_PCM_BYTES)
    try {
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风暂不可用" }
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法启动麦克风" }
        val start = android.os.SystemClock.elapsedRealtime()
        var size = 0
        while (!stopped.get() && size < pcm.size && android.os.SystemClock.elapsedRealtime() - start < 30_000L) {
            currentCoroutineContext().ensureActive()
            val count = audio.read(pcm, size, minOf(chunk, pcm.size - size), AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { "麦克风录音已中断" }
            if (count == 0) delay(18) else size += count
        }
        if (size >= SAMPLE_RATE / 2) speechWav(pcm, size and -2) else null
    } finally {
        if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
        audio.release()
        pcm.fill(0)
    }
}

@Composable
internal fun AssistantVoiceInput(
    session: UserFeatureSession, enabled: Boolean, onText: (String) -> Unit,
    content: @Composable (voiceButton: @Composable () -> Unit, voiceFeedback: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onText)
    val available by rememberUpdatedState(enabled)
    val stop = remember { AtomicBoolean(false) }
    var recording by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var offerSystem by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val systemVoice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && runCatching { session.current() }.isSuccess) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.trim()?.takeIf(String::isNotBlank)?.let(callback)
        }
    }
    fun start() {
        if (!available || recording || uploading || runCatching { session.current() }.isFailure) return
        stop.set(false)
        hint = null
        recording = true
        job = scope.launch {
            try {
                val wav = recordSpeech(stop)
                recording = false
                if (wav == null) { hint = "录音太短，请再试一次"; return@launch }
                uploading = true
                if (!session.run {
                    try {
                        callback(transcribeAssistant(wav))
                        hint = "已填入输入框，可编辑后发送"
                    } catch (problem: Exception) {
                        if (problem is CancellationException) throw problem
                        offerSystem = true
                        throw problem
                    } finally { wav.fill(0); uploading = false }
                }) { wav.fill(0); uploading = false }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                hint = "麦克风录音不可用"
                offerSystem = true
            } finally { recording = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else { hint = "请允许麦克风权限后录音"; offerSystem = true }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) job?.cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); job?.cancel() }
    }
    content(
        {
            IconButton(enabled = recording || enabled && !uploading, onClick = {
                if (recording) stop.set(true)
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.testTag("assistant-voice-input")) {
                Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic, when {
                    recording -> "结束录音"
                    uploading -> "正在识别"
                    else -> "语音输入"
                })
            }
        },
        {
            if (recording || uploading || hint != null || offerSystem) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val feedback = when {
                        recording -> "正在录音，最长 30 秒"
                        uploading -> "正在识别…"
                        else -> hint
                    }
                    feedback?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (offerSystem && !recording && !uploading) TextButton(enabled = enabled, onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        if (runCatching { systemVoice.launch(intent) }.isFailure) hint = "此设备未安装系统听写服务"
                    }) { Text("使用系统听写") }
                }
            }
        },
    )
}
