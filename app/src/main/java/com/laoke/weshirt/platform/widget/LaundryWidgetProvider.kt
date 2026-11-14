package com.laoke.weshirt.platform.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.NativeNavigation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LaundryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = LaundryWidgetStore.read(context)
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, adaptiveViews(context, id, manager.getAppWidgetOptions(id), snapshot))
        }
        // A host redraw and a local minute tick never fetch account data.
        if (appWidgetIds.isNotEmpty()) LaundryWidgetRefresh.scheduleTicks(context)
    }

    override fun onEnabled(context: Context) { LaundryWidgetRefresh.scheduleTicks(context) }
    override fun onDisabled(context: Context) { LaundryWidgetRefresh.cancel(context) }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val remaining = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, LaundryWidgetProvider::class.java))
            .filterNot { it in appWidgetIds }
        if (remaining.isEmpty()) LaundryWidgetRefresh.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val installed = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, LaundryWidgetProvider::class.java))
            acceptedRefreshId(context, intent, installed)?.let { LaundryWidgetRefresh.enqueue(context, it) }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        manager.updateAppWidget(appWidgetId, adaptiveViews(context, appWidgetId, newOptions, LaundryWidgetStore.read(context)))
    }

    private fun adaptiveViews(context: Context, id: Int, options: Bundle, snapshot: LaundryWidgetSnapshot?): RemoteViews {
        val now = System.currentTimeMillis()
        val fontScale = context.resources.configuration.fontScale
        if (Build.VERSION.SDK_INT >= 31 && fontScale <= 1.5f) {
            return RemoteViews(mapOf(
                SizeF(180f, 140f) to render(context, id, snapshot, WidgetSize.COMPACT, now),
                SizeF(230f, 140f) to render(context, id, snapshot, WidgetSize.WIDE, now),
                SizeF(230f, 320f) to render(context, id, snapshot, WidgetSize.EXPANDED, now),
            ))
        }
        return render(context, id, snapshot, LaundryWidgetState.size(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140), fontScale), now)
    }

    internal fun render(context: Context, id: Int, snapshot: LaundryWidgetSnapshot?, size: WidgetSize, now: Long): RemoteViews {
        val layout = when (size) {
            WidgetSize.COMPACT -> R.layout.laundry_widget
            WidgetSize.WIDE -> R.layout.laundry_widget_wide
            WidgetSize.EXPANDED -> R.layout.laundry_widget_expanded
        }
        val views = RemoteViews(context.packageName, layout)
        val freshness = LaundryWidgetState.freshness(snapshot, now)
        val taskState = LaundryWidgetState.taskState(snapshot, now)
        val hasSession = LaundryWidgetStore.captureScope(context) != null
        val refreshError = if (hasSession) LaundryWidgetStore.refreshError(context) else null
        val running = LaundryWidgetState.isRunning(snapshot, now)
        val remaining = LaundryWidgetState.remainingMinutes(snapshot, now)
        val progress = LaundryWidgetState.progressPercent(snapshot, now)
        val building = snapshot?.buildingName?.let(LaundryWidgetState::safeLabel)?.takeIf(String::isNotBlank)
            ?: context.getString(if (snapshot == null) R.string.widget_brand else R.string.widget_current_building)
        val time = snapshot?.updatedAtMillis?.takeIf { freshness == WidgetFreshness.RECENT || freshness == WidgetFreshness.OUTDATED }
            ?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
        val updated = if (time == null) context.getString(R.string.widget_update_unknown)
            else context.getString(R.string.widget_last_sync, time)
        val machine = snapshot?.taskMachineName?.let(LaundryWidgetState::safeLabel)?.takeIf(String::isNotBlank)
        val title = machine ?: context.getString(when {
            snapshot == null && !hasSession -> R.string.widget_sign_in
            taskState == WidgetTaskState.NONE && snapshot != null -> R.string.widget_idle
            else -> R.string.widget_pending
        })
        val taskStatus = when (taskState) {
            WidgetTaskState.NONE -> context.getString(when {
                refreshError != null -> R.string.widget_refresh_failed
                snapshot == null && hasSession -> R.string.widget_tap_refresh
                snapshot == null -> R.string.widget_sign_in_detail
                else -> R.string.widget_no_task
            })
            WidgetTaskState.ESTIMATED -> remaining?.takeIf { it > 0 }?.let { context.getString(R.string.widget_remaining, it) }
                ?: context.getString(R.string.widget_awaiting)
            WidgetTaskState.AWAITING_CONFIRMATION -> context.getString(R.string.widget_awaiting)
            WidgetTaskState.READY_FOR_PICKUP -> context.getString(R.string.widget_ready_for_pickup)
            WidgetTaskState.UNVERIFIED -> context.getString(if (refreshError == null) R.string.widget_unverified else R.string.widget_refresh_failed)
        }
        val description = context.getString(R.string.widget_task_accessibility, title, taskStatus)
        val board = NativeNavigation.pendingIntent(context, "LAUNDRY_BOARD", "widget:$id:board")
        val task = NativeNavigation.pendingIntent(context, "MY_LAUNDRY", "widget:$id:task")
        views.setTextViewText(R.id.widget_building, building)
        views.setTextViewText(R.id.widget_updated, updated)
        views.setTextViewText(R.id.widget_task, title)
        views.setTextViewText(R.id.widget_task_eta, taskStatus)
        views.setContentDescription(R.id.widget_root, "$building. $updated. $description" + refreshError?.let { ". $it" }.orEmpty())
        views.setContentDescription(R.id.widget_updated, if (freshness == WidgetFreshness.OUTDATED)
            context.getString(R.string.widget_old_data, time) else updated)
        views.setOnClickPendingIntent(R.id.widget_building, board)
        views.setOnClickPendingIntent(R.id.widget_root, if (machine == null) board else task)
        views.setOnClickPendingIntent(R.id.widget_task_section, task)
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, id))
        views.setViewVisibility(R.id.widget_drum_static, if (running) View.GONE else View.VISIBLE)
        // Remove the animated subtree on idle/stale redraws. A hidden auto-start
        // ViewFlipper would otherwise keep its timer alive in some launchers.
        views.removeAllViews(R.id.widget_drum_holder)
        if (running) views.addView(R.id.widget_drum_holder, RemoteViews(context.packageName, R.layout.laundry_widget_drum))
        views.setViewVisibility(R.id.widget_drum_holder, if (running) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_activity, if (running) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_progress, if (progress != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_progress_label, if (machine != null) View.VISIBLE else View.GONE)
        views.setProgressBar(R.id.widget_progress, 100, progress ?: 0, false)
        views.setTextViewText(R.id.widget_progress_label, progress?.let { context.getString(R.string.widget_progress_percent, it) }
            ?: context.getString(R.string.widget_progress_unknown))
        views.setContentDescription(R.id.widget_machine, context.getString(if (running) R.string.widget_machine_running else R.string.widget_machine_still))
        views.setViewVisibility(R.id.widget_task_note, if (size == WidgetSize.EXPANDED) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_task_note, refreshError ?: context.getString(when {
            freshness == WidgetFreshness.OUTDATED -> R.string.widget_stale_hint
            taskState == WidgetTaskState.AWAITING_CONFIRMATION -> R.string.widget_awaiting_detail
            machine == null -> R.string.widget_idle_detail
            else -> R.string.widget_expected_disclaimer
        }))
        return views
    }

    internal fun refreshPendingIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id, Intent(context, LaundryWidgetProvider::class.java).setAction(ACTION_REFRESH)
            .setData(Uri.parse("weshirt-widget://refresh/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun acceptedRefreshId(context: Context, intent: Intent, installed: IntArray): Int? {
        if (intent.action != ACTION_REFRESH || intent.component != ComponentName(context, LaundryWidgetProvider::class.java)) return null
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return id.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID && it in installed }
    }

    companion object { internal const val ACTION_REFRESH = "com.laoke.weshirt.action.REFRESH_WIDGET" }
}
