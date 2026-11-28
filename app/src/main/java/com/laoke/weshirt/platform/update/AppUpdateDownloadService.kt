package com.laoke.weshirt.platform.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.NativeNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Only a user tap starts this bounded, cancellable transfer. No boot restart or silent install. */
class AppUpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellation = UpdateDownloadCancellation()
    private var requestId: String? = null
    private var worker: Job? = null
    private val notificationLock = Any()
    private var stopping = false
    private var meter = UpdateDownloadMeter()
    private val downloaded = AtomicLong()
    @Volatile private var verifying = false
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(REQUEST)
        if (intent?.action == CANCEL) {
            if (id != null && id == requestId) {
                cancellation.cancel()
                // Keep the single service slot until the HTTP call has unwound and deleted .part.
                // A quick retry must not overlap a still-closing transfer or be stopped by it.
            } else if (requestId == null) stopSelf()
            return START_NOT_STICKY
        }
        val current = AppUpdateDownloads.state.value
        if (intent?.action != START || id == null || id != current.requestId || !current.active || current.release == null) {
            if (requestId == null) stopSelf()
            return START_NOT_STICKY
        }
        if (requestId != null) return START_NOT_STICKY
        requestId = id
        val release = current.release
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新下载", NotificationManager.IMPORTANCE_LOW))
        try {
            // Start immediately, before DNS, disk access or package validation.
            startForeground(NOTICE, notification(release, UpdateDownloadMetrics(0, release.size)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (error: RuntimeException) {
            AppUpdateDownloads.fail(id, error, "DOWNLOAD_START_FAILED", "无法开始下载，请返回应用后重试")
            stopSelf()
            return START_NOT_STICKY
        }
        val ticker = scope.launch {
            while (isActive) {
                val metrics = meter.sample(downloaded.get(), release.size, SystemClock.elapsedRealtime())
                AppUpdateDownloads.progress(id, metrics, verifying)
                publishProgress(release, metrics)
                delay(1000)
            }
        }
        worker = scope.launch {
            var apk: File? = null
            try {
                val directory = File(cacheDir, "app-updates")
                // A killed process is deliberately not restarted. Its unfinished temporary file
                // is discarded when the user explicitly begins the next download.
                directory.listFiles()?.filter { it.name.startsWith("download-") && it.name.endsWith(".part") }?.forEach { it.delete() }
                apk = AppUpdateClient(BuildConfig.API_BASE_URL).downloadCancelable(release,
                    directory, cancellation) { downloaded.set(it) }
                cancellation.check()
                verifying = true
                val metrics = UpdateDownloadMetrics(release.size, release.size)
                AppUpdateDownloads.progress(id, metrics, verifying = true)
                publishProgress(release, metrics)
                AppPackageVerifier.verify(this@AppUpdateDownloadService, apk, release)
                cancellation.check()
                AppUpdateDownloads.ready(id, apk)
            } catch (_: CancellationException) {
                apk?.delete()
                AppUpdateDownloads.cancelled(id)
            } catch (error: Exception) {
                apk?.delete()
                AppUpdateDownloads.fail(id, error)
            } finally {
                withContext(NonCancellable) {
                    // Cancellation is cooperative: wait for the ticker's last notify to return too.
                    ticker.cancelAndJoin()
                    synchronized(notificationLock) {
                        if (!stopping) {
                            stopping = true
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            val result = AppUpdateDownloads.state.value
                            // No Activity is launched from the background. This opens settings only after a tap.
                            if (result.requestId == id && (result.apk != null || result.error != null))
                                runCatching { manager.notify(NOTICE, resultNotification(release, result.apk != null)) }
                        }
                    }
                    stopSelf()
                }
            }
        }.also { job -> job.invokeOnCompletion { AppUpdateDownloads.workerFinished(id) } }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellation.cancel()
        requestId?.let { AppUpdateDownloads.cancelled(it, "下载已被系统暂停，请返回应用重新下载") }
        withdrawProgress()
        stopSelf()
    }

    override fun onDestroy() {
        cancellation.cancel()
        requestId?.let { AppUpdateDownloads.cancelled(it, "下载已停止，可重新下载") }
        withdrawProgress()
        scope.cancel()
        super.onDestroy()
        requestId?.let {
            AppUpdateDownloads.serviceDestroyed(it)
            // A foreground-start failure has no coroutine to deliver completion.
            if (worker == null) AppUpdateDownloads.workerFinished(it)
        }
    }

    private fun publishProgress(release: AppRelease, metrics: UpdateDownloadMetrics) = synchronized(notificationLock) {
        if (!stopping) manager.notify(NOTICE, notification(release, metrics))
    }

    private fun withdrawProgress() = synchronized(notificationLock) {
        if (!stopping) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun notification(release: AppRelease, metrics: UpdateDownloadMetrics): Notification {
        val cancel = PendingIntent.getService(this, NOTICE, Intent(this, AppUpdateDownloadService::class.java)
            .setAction(CANCEL).putExtra(REQUEST, requestId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (verifying) "正在验证安装包签名" else metrics.description()
        return builder().setContentTitle("下载更新 · v${release.versionName}").setContentText(text)
            .setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(1000, (metrics.fraction * 1000).toInt(), verifying)
            .addAction(Notification.Action.Builder(null, "取消下载", cancel).build()).build()
    }

    private fun resultNotification(release: AppRelease, success: Boolean): Notification = builder()
        .setContentTitle(if (success) "WeShirt v${release.versionName} 已下载" else "WeShirt 更新下载未完成")
        .setContentText(if (success) "安装包与签名已验证，点击返回应用确认安装" else "点击返回应用查看原因并重试")
        .setAutoCancel(true).build()

    private fun builder() = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_laundry)
        .setColor(android.graphics.Color.rgb(41, 101, 217)).setShowWhen(false)
        .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(NativeNavigation.pendingIntent(this, "APP_UPDATE", "app-update"))

    companion object {
        internal const val START = "weshirt.update.START"
        internal const val CANCEL = "weshirt.update.CANCEL"
        internal const val REQUEST = "weshirt.update.request"
        private const val CHANNEL = "app_update_downloads"
        private const val NOTICE = 9174
    }
}
