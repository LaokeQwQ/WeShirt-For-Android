package com.laoke.weshirt.platform.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.push.EventRevisionLedger
import com.laoke.weshirt.platform.push.NativePushEvent
import com.laoke.weshirt.platform.push.PushEventKind
import com.laoke.weshirt.platform.push.PushRuntime
import org.json.JSONObject

data class TaskProgress(
    val taskId: String,
    val revision: Long,
    val machineName: String,
    val startedAtMillis: Long,
    val estimatedFinishAtMillis: Long,
    val active: Boolean = true,
    /** Promotion requires the user's explicit choice to monitor this particular active task. */
    val userRequestedTracking: Boolean = false,
)

data class NotificationCapabilities(val permissionGranted: Boolean, val notificationsEnabled: Boolean, val liveUpdatesAllowed: Boolean)

class LaundryNotifications(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun capabilities(): NotificationCapabilities {
        val granted = Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return NotificationCapabilities(granted, manager.areNotificationsEnabled(),
            Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications())
    }

    /** Returns true only when a valid, newer revision was actually posted. */
    @SuppressLint("MissingPermission") // Permission is checked by capabilities immediately before delivery.
    fun post(event: NativePushEvent): Boolean = synchronized(lock) {
        if (!event.valid() || !PushRuntime.isSessionActive(app)) return false
        val capability = capabilities()
        if (!capability.permissionGranted || !capability.notificationsEnabled) return false
        val ledger = loadLedger()
        if (!ledger.shouldAccept(event.eventId, event.revision)) return false
        createChannels()
        val channel = if (event.kind == PushEventKind.CHAT_MENTION) CHAT_CHANNEL else ALERT_CHANNEL
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val notification = Notification.Builder(app, channel)
            .setSmallIcon(R.drawable.notification_laundry)
            .setContentTitle(event.title).setContentText(event.body)
            .setStyle(Notification.BigTextStyle().bigText(event.body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(app, channel).setSmallIcon(R.drawable.notification_laundry)
                .setContentTitle("WeShirt").setContentText("有一条新的洗衣提醒").build())
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp(event.kind.name, event.taskId, event.eventId)).build()
        try {
            manager.notify("event:${event.eventId}", 0, notification)
            ledger.record(event.eventId, event.revision)
            prefs.edit().putString("ledger", JSONObject(ledger.entries()).toString()).commit()
            true
        } catch (_: SecurityException) { false }
    }

    /** Uses Android's own chronometer; no foreground polling loop or background keepalive. */
    @SuppressLint("MissingPermission")
    fun updateProgress(progress: TaskProgress): Boolean = synchronized(lock) {
        val tag = "progress:${progress.taskId}"
        if (!progress.active) { manager.cancel(tag, 0); return true }
        if (!PushRuntime.isSessionActive(app)) return false
        if (progress.taskId.isBlank() || progress.revision < 0 || progress.estimatedFinishAtMillis <= 0 ||
            progress.startedAtMillis > 0 && (progress.estimatedFinishAtMillis <= progress.startedAtMillis ||
            progress.estimatedFinishAtMillis - progress.startedAtMillis > 24 * 60 * 60_000L)) return false
        if (prefs.getBoolean("dismissed:${progress.taskId}", false)) return false
        val capability = capabilities()
        if (!capability.permissionGranted || !capability.notificationsEnabled) return false
        val previousRevision = prefs.getLong("progressRevision:${progress.taskId}", -1L)
        if (progress.revision < previousRevision) return false
        createChannels()
        val now = System.currentTimeMillis()
        val duration = (progress.estimatedFinishAtMillis - progress.startedAtMillis).coerceAtLeast(1)
        val elapsed = (now - progress.startedAtMillis).coerceIn(0L, duration)
        val expectedEnded = now >= progress.estimatedFinishAtMillis
        val builder = Notification.Builder(app, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.notification_laundry)
            .setContentTitle(progress.machineName.take(100))
            .setContentText(if (expectedEnded) "预计时间已到，实际状态以设备更新为准" else "正在洗衣 · 剩余时间为估计值")
            .setOngoing(!expectedEnded).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(if (progress.startedAtMillis > 0) 1000 else 0,
                if (progress.startedAtMillis > 0) ((elapsed * 1000) / duration).toInt() else 0, progress.startedAtMillis <= 0)
            .setContentIntent(openApp("MY_LAUNDRY", progress.taskId, progress.taskId))
            .setDeleteIntent(PendingIntent.getBroadcast(app, progress.taskId.hashCode(),
                Intent(app, NotificationDismissReceiver::class.java).setAction("dismiss:${progress.taskId}")
                    .putExtra("taskId", progress.taskId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (!expectedEnded) builder.setWhen(progress.estimatedFinishAtMillis).setUsesChronometer(true).setChronometerCountDown(true)
        if (Build.VERSION.SDK_INT >= 36 && capability.liveUpdatesAllowed && progress.userRequestedTracking && !expectedEnded) {
            // This is the same public extras contract used by NotificationCompat on API 36.
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        }
        try {
            manager.notify(tag, 0, builder.build())
            prefs.edit().putLong("progressRevision:${progress.taskId}", progress.revision).apply()
            true
        } catch (_: SecurityException) { false }
    }

    fun cancelProgress(taskId: String) { manager.cancel("progress:$taskId", 0) }
    fun clear() { synchronized(lock) { manager.cancelAll(); prefs.edit().clear().commit() } }

    private fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(ALERT_CHANNEL, "洗衣与代取提醒", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHAT_CHANNEL, "频道提及", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(PROGRESS_CHANNEL, "洗衣进度", NotificationManager.IMPORTANCE_LOW),
        ))
    }

    private fun openApp(destination: String, taskId: String?, identity: String): PendingIntent? =
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent.action = "weshirt.open.$identity"
            intent.putExtra("weshirt.destination", destination)
            taskId?.let { intent.putExtra("weshirt.taskId", it.take(160)) }
            PendingIntent.getActivity(app, identity.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

    private fun loadLedger(): EventRevisionLedger {
        val ledger = EventRevisionLedger()
        runCatching { JSONObject(prefs.getString("ledger", "{}")!!) }.getOrNull()?.let { json ->
            json.keys().forEach { ledger.record(it, json.optLong(it, -1)) }
        }
        return ledger
    }

    companion object {
        internal const val PREFS = "notification_delivery"
        private const val ALERT_CHANNEL = "laundry_alerts"
        private const val CHAT_CHANNEL = "chat_mentions"
        private const val PROGRESS_CHANNEL = "laundry_progress"
        private val lock = Any()
    }
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("taskId")?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return
        context.getSharedPreferences(LaundryNotifications.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("dismissed:$taskId", true).apply()
    }
}
