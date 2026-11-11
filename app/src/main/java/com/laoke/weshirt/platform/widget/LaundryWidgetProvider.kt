package com.laoke.weshirt.platform.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.NativeNavigation
import com.laoke.weshirt.platform.push.PushRuntime
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Deliberately excludes account, chat, phone, remarks, avatar, and provider tokens. */
data class LaundryWidgetSnapshot(
    val buildingName: String,
    val idle: Int,
    val busy: Int,
    val unknown: Int,
    val updatedAtMillis: Long,
    val taskMachineName: String? = null,
    val taskEstimatedFinishAtMillis: Long? = null,
)

object LaundryWidgetStore {
    private const val PREFS = "laundry_widget_snapshot"
    @Synchronized fun update(context: Context, snapshot: LaundryWidgetSnapshot) {
        if (!PushRuntime.isSessionActive(context)) return
        val value = JSONObject().put("building", LaundryWidgetState.safeLabel(snapshot.buildingName))
            .put("idle", snapshot.idle.coerceAtLeast(0)).put("busy", snapshot.busy.coerceAtLeast(0))
            .put("unknown", snapshot.unknown.coerceAtLeast(0)).put("updated", snapshot.updatedAtMillis)
            .put("machine", snapshot.taskMachineName?.let(LaundryWidgetState::safeLabel)).put("finish", snapshot.taskEstimatedFinishAtMillis)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("snapshot", value.toString()).commit()
        refresh(context)
    }

    @Synchronized internal fun read(context: Context): LaundryWidgetSnapshot? {
        if (!PushRuntime.isSessionActive(context)) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("snapshot", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            LaundryWidgetSnapshot(json.getString("building"), json.getInt("idle"), json.getInt("busy"),
                json.getInt("unknown"), json.getLong("updated"),
                if (json.isNull("machine")) null else json.getString("machine"),
                if (json.isNull("finish")) null else json.getLong("finish"))
        }.getOrNull()
    }

    @Synchronized fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit(); refresh(context) }
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, LaundryWidgetProvider::class.java))
        LaundryWidgetProvider().onUpdate(context, manager, ids)
    }
}

class LaundryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = LaundryWidgetStore.read(context)
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, adaptiveViews(context, id, manager.getAppWidgetOptions(id), snapshot))
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        manager.updateAppWidget(appWidgetId, adaptiveViews(context, appWidgetId, newOptions, LaundryWidgetStore.read(context)))
    }

    private fun adaptiveViews(context: Context, id: Int, options: Bundle, snapshot: LaundryWidgetSnapshot?): RemoteViews {
        val now = System.currentTimeMillis()
        val fontScale = context.resources.configuration.fontScale
        if (Build.VERSION.SDK_INT >= 31 && fontScale <= 1.5f) {
            return RemoteViews(mapOf(
                SizeF(110f, 110f) to render(context, id, snapshot, WidgetSize.COMPACT, now),
                SizeF(230f, 140f) to render(context, id, snapshot, WidgetSize.WIDE, now),
                SizeF(230f, 320f) to render(context, id, snapshot, WidgetSize.EXPANDED, now),
            ))
        }
        val size = LaundryWidgetState.size(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140), fontScale)
        return render(context, id, snapshot, size, now)
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
        val hasCounts = freshness == WidgetFreshness.RECENT || freshness == WidgetFreshness.OUTDATED
        val building = snapshot?.buildingName?.let(LaundryWidgetState::safeLabel)?.takeIf(String::isNotBlank)
            ?: context.getString(if (snapshot == null) R.string.widget_brand else R.string.widget_current_building)
        val idle = snapshot?.idle?.coerceAtLeast(0) ?: 0
        val busy = snapshot?.busy?.coerceAtLeast(0) ?: 0
        val unknown = snapshot?.unknown?.coerceAtLeast(0) ?: 0
        val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val updated = when (freshness) {
            WidgetFreshness.MISSING -> context.getString(R.string.widget_sign_in_detail)
            WidgetFreshness.INVALID_TIME -> context.getString(R.string.widget_time_unknown)
            WidgetFreshness.OUTDATED -> context.getString(R.string.widget_old_data, dateFormat.format(Date(snapshot!!.updatedAtMillis)))
            WidgetFreshness.RECENT -> context.getString(R.string.widget_last_sync, timeFormat.format(Date(snapshot!!.updatedAtMillis)))
        }
        val finish = snapshot?.taskEstimatedFinishAtMillis
        val expected = finish?.takeIf { it > 0L }?.let { timeFormat.format(Date(it)) }
        val taskStatus = when (taskState) {
            WidgetTaskState.NONE -> context.getString(if (snapshot == null) R.string.widget_sign_in_detail else R.string.widget_no_task)
            WidgetTaskState.ESTIMATED -> context.getString(R.string.widget_expected_at, expected)
            WidgetTaskState.AWAITING_CONFIRMATION -> context.getString(R.string.widget_awaiting)
            WidgetTaskState.UNVERIFIED -> context.getString(R.string.widget_unverified)
        }
        val countSummary = if (hasCounts) context.getString(R.string.widget_counts_all, idle, busy, unknown) else context.getString(R.string.widget_open_refresh)
        val taskName = snapshot?.taskMachineName?.let(LaundryWidgetState::safeLabel)?.takeIf(String::isNotBlank)
        val boardDescription = context.getString(R.string.widget_board_accessibility, building, countSummary, updated)
        val taskDescription = context.getString(R.string.widget_task_accessibility, taskName ?: context.getString(R.string.widget_my_laundry), taskStatus)
        val boardIntent = NativeNavigation.pendingIntent(context, "LAUNDRY_BOARD", "widget:$id:board")
        val taskIntent = NativeNavigation.pendingIntent(context, "MY_LAUNDRY", "widget:$id:task")
        views.setTextViewText(R.id.widget_building, building)
        views.setTextViewText(R.id.widget_updated, updated)
        views.setOnClickPendingIntent(R.id.widget_building, boardIntent)
        views.setOnClickPendingIntent(R.id.widget_root, boardIntent)
        views.setContentDescription(R.id.widget_root, boardDescription)
        views.setTextViewText(R.id.widget_primary, if (hasCounts) idle.toString() else "—")
        views.setTextViewText(R.id.widget_primary_label, context.getString(if (snapshot == null) R.string.widget_sign_in else if (hasCounts) R.string.widget_idle_label else R.string.widget_open_refresh))
        if (size == WidgetSize.COMPACT) {
            // Keep the narrow card readable at large font sizes. The full summary remains accessible.
            views.setViewVisibility(R.id.widget_brand, View.GONE)
            if (taskName != null) {
                views.setTextViewText(R.id.widget_primary, if (taskState == WidgetTaskState.ESTIMATED) expected else "—")
                views.setTextViewText(R.id.widget_primary_label, if (taskState == WidgetTaskState.ESTIMATED) context.getString(R.string.widget_expected_label) else taskStatus)
                views.setContentDescription(R.id.widget_root, "$boardDescription. $taskDescription")
                views.setOnClickPendingIntent(R.id.widget_root, taskIntent)
            }
            return views
        }
        views.setTextViewText(R.id.widget_task, taskName ?: context.getString(R.string.widget_my_laundry))
        views.setTextViewText(R.id.widget_task_eta, taskStatus)
        views.setOnClickPendingIntent(R.id.widget_board, boardIntent)
        views.setContentDescription(R.id.widget_board, boardDescription)
        views.setOnClickPendingIntent(R.id.widget_task_section, taskIntent)
        views.setContentDescription(R.id.widget_task_section, taskDescription)
        if (size == WidgetSize.WIDE) {
            views.setTextViewText(R.id.widget_summary, if (hasCounts) context.getString(R.string.widget_counts, busy, unknown) else context.getString(R.string.widget_open_refresh))
        } else {
            views.setTextViewText(R.id.widget_busy, if (hasCounts) busy.toString() else "—")
            views.setTextViewText(R.id.widget_unknown, if (hasCounts) unknown.toString() else "—")
            views.setTextViewText(R.id.widget_task_note, context.getString(if (freshness == WidgetFreshness.RECENT) R.string.widget_expected_disclaimer else R.string.widget_stale_hint))
            views.setOnClickPendingIntent(R.id.widget_board_action, boardIntent)
            views.setOnClickPendingIntent(R.id.widget_task_action, taskIntent)
        }
        return views
    }
}
