package com.laoke.weshirt.platform.update

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

internal data class UpdateDownloadState(
    val requestId: String = "",
    val release: AppRelease? = null,
    val active: Boolean = false,
    val metrics: UpdateDownloadMetrics? = null,
    val verifying: Boolean = false,
    val apk: File? = null,
    val status: String? = null,
    val error: String? = null,
)

/** Application-scoped transfer state survives Activity recreation without keeping an Activity alive. */
internal object AppUpdateDownloads {
    private val mutableState = MutableStateFlow(UpdateDownloadState())
    val state = mutableState.asStateFlow()
    private val lease = UpdateDownloadLease()

    @Synchronized fun start(context: Context, release: AppRelease) {
        if (mutableState.value.active) return
        val request = UUID.randomUUID().toString()
        if (!lease.acquire(request)) {
            mutableState.value = mutableState.value.copy(status = "正在结束上次下载，请稍候再试")
            return
        }
        mutableState.value = UpdateDownloadState(request, release, active = true,
            metrics = UpdateDownloadMetrics(0, release.size))
        try {
            context.startForegroundService(Intent(context, AppUpdateDownloadService::class.java)
                .setAction(AppUpdateDownloadService.START).putExtra(AppUpdateDownloadService.REQUEST, request))
        } catch (error: RuntimeException) {
            fail(request, error, "DOWNLOAD_START_FAILED", "无法开始后台下载，请返回应用后重试")
            lease.startRejected(request)
        }
    }
    fun serviceDestroyed(id: String) { lease.serviceDestroyed(id) }
    fun workerFinished(id: String) { lease.workerFinished(id) }

    fun cancel(context: Context) {
        val current = mutableState.value
        if (!current.active) return
        try {
            context.startService(Intent(context, AppUpdateDownloadService::class.java)
                .setAction(AppUpdateDownloadService.CANCEL).putExtra(AppUpdateDownloadService.REQUEST, current.requestId))
        } catch (_: RuntimeException) { context.stopService(Intent(context, AppUpdateDownloadService::class.java)) }
    }

    @Synchronized fun progress(id: String, metrics: UpdateDownloadMetrics, verifying: Boolean = false) {
        val current = mutableState.value
        if (current.requestId == id && current.active) mutableState.value = current.copy(metrics = metrics, verifying = verifying)
    }
    @Synchronized fun ready(id: String, apk: File) {
        val current = mutableState.value
        if (current.requestId == id && current.active) mutableState.value = current.copy(active = false, apk = apk,
            verifying = false, status = "安装包已验证，点击安装更新并在系统界面确认。")
    }
    @Synchronized fun cancelled(id: String, message: String = "下载已取消") {
        val current = mutableState.value
        if (current.requestId == id && current.active) mutableState.value = current.copy(active = false, verifying = false, status = message)
    }
    @Synchronized fun fail(id: String, error: Exception, code: String = "DOWNLOAD_FAILED", message: String = "下载更新失败，请稍后重试") {
        val current = mutableState.value
        if (current.requestId != id || !current.active) return
        mutableState.value = current.copy(active = false, verifying = false, error = describeUpdateError(error, code, message))
    }
}

internal fun describeUpdateError(problem: Exception, fallbackCode: String, fallbackMessage: String): String {
    val error = problem as? AppUpdateException
    val event = "ota_" + UUID.randomUUID().toString().replace("-", "")
    val code = error?.code ?: fallbackCode
    Log.w("WeShirtUpdate", "$event $code ${problem.javaClass.simpleName}")
    return "${error?.message ?: fallbackMessage}（$code，事件 $event）"
}
