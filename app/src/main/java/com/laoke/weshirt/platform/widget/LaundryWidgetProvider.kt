package com.laoke.weshirt.platform.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.NativeNavigation
import java.text.SimpleDateFormat
import java.util.Date

/** The original component remains the 4×2 entry so installed widgets keep working. */
open class LaundryWidgetProvider : AppWidgetProvider() {
    internal open val widgetSize: WidgetSize = WidgetSize.WIDE

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        LaundryWidgetTheme.observe(context)
        val snapshot = LaundryWidgetStore.read(context)
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, render(context, id, snapshot, widgetSize, System.currentTimeMillis()))
        }
        // A host redraw and a local minute tick never fetch account data.
        if (appWidgetIds.isNotEmpty()) LaundryWidgetRefresh.scheduleTicks(context)
    }

    override fun onEnabled(context: Context) { LaundryWidgetRefresh.scheduleTicks(context) }
    override fun onDisabled(context: Context) {
        if (LaundryWidgetRegistry.remainingIds(context).isEmpty()) LaundryWidgetRefresh.cancel(context)
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        if (LaundryWidgetRegistry.remainingIds(context, appWidgetIds).isEmpty()) LaundryWidgetRefresh.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val installed = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, javaClass))
            acceptedRefreshId(context, intent, installed)?.let { LaundryWidgetRefresh.enqueue(context, it) }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        manager.updateAppWidget(appWidgetId,
            render(context, appWidgetId, LaundryWidgetStore.read(context), widgetSize, System.currentTimeMillis()))
    }

    internal fun render(context: Context, id: Int, snapshot: LaundryWidgetSnapshot?, size: WidgetSize, now: Long): RemoteViews {
        val layout = when (size) {
            WidgetSize.MINI -> R.layout.laundry_widget_mini
            WidgetSize.COMPACT -> R.layout.laundry_widget
            WidgetSize.WIDE -> R.layout.laundry_widget_wide
        }
        val views = RemoteViews(context.packageName, layout)
        val freshness = LaundryWidgetState.freshness(snapshot, now)
        val taskState = LaundryWidgetState.taskState(snapshot, now)
        val hasSession = LaundryWidgetStore.captureScope(context) != null
        val refreshError = if (hasSession) LaundryWidgetStore.refreshError(context) else null
        val running = LaundryWidgetState.isRunning(snapshot, now)
        val progress = LaundryWidgetState.progressPercent(snapshot, now)
        val building = snapshot?.buildingName?.let(LaundryWidgetState::safeLabel)?.takeIf(String::isNotBlank)
            ?: context.getString(if (snapshot == null) R.string.widget_brand else R.string.widget_current_building)
        val time = snapshot?.updatedAtMillis?.takeIf { freshness == WidgetFreshness.RECENT || freshness == WidgetFreshness.OUTDATED }
            ?.let { widgetTime(context, it) }
        val updated = if (time == null) context.getString(R.string.widget_update_unknown)
            else context.getString(R.string.widget_last_sync, time)
        val machine = snapshot?.taskMachineName?.let(LaundryWidgetState::safeLabel)?.takeIf(String::isNotBlank)
        val title = machine?.let { widgetTaskName(snapshot?.buildingName, it) } ?: context.getString(when {
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
            WidgetTaskState.ESTIMATED -> snapshot?.taskEstimatedFinishAtMillis?.takeIf { it > now }
                ?.let { context.getString(R.string.widget_expected_finish, widgetTime(context, it)) }
                ?: context.getString(R.string.widget_awaiting)
            WidgetTaskState.AWAITING_CONFIRMATION -> context.getString(R.string.widget_awaiting)
            WidgetTaskState.READY_FOR_PICKUP -> context.getString(R.string.widget_ready_for_pickup)
            WidgetTaskState.UNVERIFIED -> context.getString(if (refreshError == null) R.string.widget_unverified else R.string.widget_refresh_failed)
        }
        val description = context.getString(R.string.widget_task_accessibility, title, taskStatus)
        val board = NativeNavigation.pendingIntent(context, "LAUNDRY_BOARD", "widget:$id:board")
        val task = NativeNavigation.pendingIntent(context, "MY_LAUNDRY", "widget:$id:task")
        views.setTextViewText(R.id.widget_building, building)
        views.setViewVisibility(R.id.widget_building, View.GONE)
        views.setTextViewText(R.id.widget_updated, time?.let { context.getString(R.string.widget_updated_compact, it) }
            ?: context.getString(R.string.widget_update_unknown))
        val visibleTitle = if (size != WidgetSize.MINI) title else when {
            machine != null -> title
            snapshot == null && !hasSession -> context.getString(R.string.widget_mini_sign_in)
            taskState != WidgetTaskState.NONE || snapshot == null -> context.getString(R.string.widget_mini_unverified)
            else -> title
        }
        views.setTextViewText(R.id.widget_task, visibleTitle)
        views.setTextViewText(R.id.widget_task_eta, if (size == WidgetSize.MINI)
            miniStatus(context, taskState, taskStatus, snapshot, hasSession, refreshError) else taskStatus)
        val accessibleProgress = context.getString(R.string.widget_progress_percent, progress ?: 0)
            .takeIf { progress != null } ?: context.getString(R.string.widget_progress_unknown)
        views.setContentDescription(R.id.widget_root, "$building. $updated. $description. $accessibleProgress" + refreshError?.let { ". $it" }.orEmpty())
        views.setContentDescription(R.id.widget_updated, if (freshness == WidgetFreshness.OUTDATED)
            context.getString(R.string.widget_old_data, time) else updated)
        views.setOnClickPendingIntent(R.id.widget_building, board)
        views.setOnClickPendingIntent(R.id.widget_root, if (machine == null) board else task)
        views.setOnClickPendingIntent(R.id.widget_task_section, task)
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, id))
        views.setViewVisibility(R.id.widget_drum_static, if (running || size == WidgetSize.MINI) View.GONE else View.VISIBLE)
        // Remove the animated subtree on idle/stale redraws. A hidden auto-start
        // ViewFlipper would otherwise keep its timer alive in some launchers.
        views.removeAllViews(R.id.widget_drum_holder)
        if (running && size != WidgetSize.MINI) views.addView(R.id.widget_drum_holder, themedDrum(context))
        views.setViewVisibility(R.id.widget_drum_holder, if (running && size != WidgetSize.MINI) View.VISIBLE else View.GONE)
        // Only the horizontal mini card uses a liquid gauge. Larger cards keep
        // that space for the washer and a single, inset progress track.
        if (size == WidgetSize.MINI) {
            val movingWater = running && progress != null
            views.removeAllViews(R.id.widget_water_holder)
            if (movingWater) views.addView(R.id.widget_water_holder, themedWater(context, progress!!))
            views.setViewVisibility(R.id.widget_water_holder, if (movingWater) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_machine_ring, if (movingWater) View.GONE else View.VISIBLE)
        }
        views.setViewVisibility(R.id.widget_activity, if (running) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_progress, if (size != WidgetSize.MINI && progress != null && Build.VERSION.SDK_INT >= 31) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_progress_track, if (size != WidgetSize.MINI && progress != null && Build.VERSION.SDK_INT < 31) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_progress_label, View.GONE)
        views.setProgressBar(R.id.widget_progress, 100, progress ?: 0, false)
        views.setTextViewText(R.id.widget_progress_label, progress?.let { context.getString(R.string.widget_progress_percent, it) }
            ?: context.getString(R.string.widget_progress_unknown))
        views.setContentDescription(R.id.widget_machine, context.getString(if (running) R.string.widget_machine_running else R.string.widget_machine_still))
        views.setViewVisibility(R.id.widget_task_note, View.GONE)
        views.setTextViewText(R.id.widget_task_note, refreshError ?: context.getString(when {
            freshness == WidgetFreshness.OUTDATED -> R.string.widget_stale_hint
            taskState == WidgetTaskState.AWAITING_CONFIRMATION -> R.string.widget_awaiting_detail
            machine == null -> R.string.widget_idle_detail
            else -> R.string.widget_expected_disclaimer
        }))
        applyTheme(context, views, size, progress)
        return views
    }

    private fun miniStatus(context: Context, state: WidgetTaskState, taskStatus: String, snapshot: LaundryWidgetSnapshot?, hasSession: Boolean, error: String?): String = when (state) {
        WidgetTaskState.ESTIMATED -> taskStatus
        WidgetTaskState.AWAITING_CONFIRMATION -> context.getString(R.string.widget_mini_awaiting)
        WidgetTaskState.READY_FOR_PICKUP -> context.getString(R.string.widget_mini_ready)
        WidgetTaskState.UNVERIFIED -> context.getString(R.string.widget_mini_unverified)
        WidgetTaskState.NONE -> context.getString(when {
            snapshot == null && !hasSession -> R.string.widget_mini_sign_in_detail
            error != null || snapshot == null -> R.string.widget_mini_unverified
            else -> R.string.widget_mini_no_task
        })
    }

    private class ThemeActions(private val context: Context, private val views: RemoteViews) {
        private val paired = Build.VERSION.SDK_INT >= 31 && LaundryWidgetTheme.followsSystem(context)
        private val selected by lazy { LaundryWidgetTheme.resolve(context) }
        private val light by lazy { LaundryWidgetTheme.resolve(context, forceDark = false) }
        private val dark by lazy { LaundryWidgetTheme.resolve(context, forceDark = true) }

        fun image(id: Int, key: String = id.toString(), draw: (WidgetArtworkPalette) -> Bitmap) {
            fun image(palette: WidgetArtworkPalette) = cachedArtwork(key, palette, draw)
            if (paired) views.setIcon(id, "setImageIcon", Icon.createWithBitmap(image(light)), Icon.createWithBitmap(image(dark)))
            else views.setImageViewBitmap(id, image(selected))
        }

        fun text(id: Int, color: (WidgetArtworkPalette) -> Int) {
            if (paired) views.setColorInt(id, "setTextColor", color(light), color(dark))
            else views.setTextColor(id, color(selected))
        }

        fun tint(id: Int, method: String, color: (WidgetArtworkPalette) -> Int) {
            if (Build.VERSION.SDK_INT < 31) return
            if (paired) views.setColorStateList(id, method, ColorStateList.valueOf(color(light)), ColorStateList.valueOf(color(dark)))
            else views.setColorStateList(id, method, ColorStateList.valueOf(color(selected)))
        }
    }

    private fun themedDrum(context: Context): RemoteViews = RemoteViews(context.packageName, R.layout.laundry_widget_drum).also { frames ->
        val actions = ThemeActions(context, frames)
        actions.image(R.id.widget_drum_frame_0, "drum:0") { LaundryWidgetArtwork.drum(it, 0) }
        actions.image(R.id.widget_drum_frame_1, "drum:1") { LaundryWidgetArtwork.drum(it, 1) }
        actions.image(R.id.widget_drum_frame_2, "drum:2") { LaundryWidgetArtwork.drum(it, 2) }
    }

    private fun themedWater(context: Context, progress: Int): RemoteViews = RemoteViews(context.packageName, R.layout.laundry_widget_water).also { frames ->
        val actions = ThemeActions(context, frames)
        listOf(R.id.widget_water_frame_0, R.id.widget_water_frame_1, R.id.widget_water_frame_2).forEachIndexed { index, id ->
            actions.image(id, "water:$progress:$index") { LaundryWidgetArtwork.water(it, progress, index) }
        }
    }

    private fun applyTheme(context: Context, views: RemoteViews, size: WidgetSize, progress: Int?) {
        val actions = ThemeActions(context, views)
        val dimensions = when (size) {
            WidgetSize.MINI -> 180 to 70
            WidgetSize.COMPACT -> 180 to 180
            WidgetSize.WIDE -> 320 to 140
        }
        // Bitmaps and text colors override host-qualified resources, including an explicitly forced app theme.
        views.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT)
        views.setInt(R.id.widget_refresh, "setBackgroundColor", Color.TRANSPARENT)
        actions.image(R.id.widget_backdrop, "background:${dimensions.first}:${dimensions.second}") { LaundryWidgetArtwork.background(it, dimensions.first, dimensions.second) }
        actions.image(R.id.widget_refresh, draw = LaundryWidgetArtwork::refresh)
        if (size == WidgetSize.MINI) {
            views.setViewVisibility(R.id.widget_machine_shell, View.GONE)
            actions.image(R.id.widget_machine_ring, "dial:$progress") { LaundryWidgetArtwork.dial(it, progress) }
        } else {
            actions.image(R.id.widget_machine_shell, draw = LaundryWidgetArtwork::shell)
            actions.image(R.id.widget_drum_static, "drum:0") { LaundryWidgetArtwork.drum(it, 0) }
            actions.image(R.id.widget_machine_ring, "ring:$progress") { LaundryWidgetArtwork.ring(it, progress) }
        }
        actions.text(R.id.widget_building) { it.primaryText }
        actions.text(R.id.widget_updated) { it.secondaryText }
        actions.text(R.id.widget_task) { it.primaryText }
        actions.text(R.id.widget_task_eta) { it.accent }
        actions.text(R.id.widget_progress_label) { it.secondaryText }
        actions.text(R.id.widget_task_note) { it.secondaryText }
        if (Build.VERSION.SDK_INT >= 31) {
            actions.tint(R.id.widget_progress, "setProgressTintList") { it.accent }
            actions.tint(R.id.widget_progress, "setProgressBackgroundTintList") { it.outline }
            actions.tint(R.id.widget_activity, "setIndeterminateTintList") { it.accent }
        } else actions.image(R.id.widget_progress_track, "track:$progress") { LaundryWidgetArtwork.track(it, progress) }
    }

    internal fun refreshPendingIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id, refreshIntent(context, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun refreshIntent(context: Context, id: Int): Intent = Intent(context, javaClass).setAction(ACTION_REFRESH)
            .setData(Uri.parse("weshirt-widget://refresh/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

    internal fun acceptedRefreshId(context: Context, intent: Intent, installed: IntArray): Int? {
        if (intent.action != ACTION_REFRESH || intent.component != ComponentName(context, javaClass)) return null
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return id.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID && it in installed }
    }

    companion object {
        internal const val ACTION_REFRESH = "com.laoke.weshirt.action.REFRESH_WIDGET"
        private data class ArtworkKey(val name: String, val palette: WidgetArtworkPalette)
        private val artwork = object : LruCache<ArtworkKey, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: ArtworkKey, value: Bitmap) = value.allocationByteCount
        }
        @Synchronized private fun cachedArtwork(name: String, palette: WidgetArtworkPalette, draw: (WidgetArtworkPalette) -> Bitmap): Bitmap {
            val key = ArtworkKey(name, palette)
            return artwork.get(key) ?: draw(palette).also { artwork.put(key, it) }
        }
    }
}

/** Location prefixes can contain floor digits; the machine's own 号 label takes precedence. */
internal fun widgetMachineNumber(machine: String): String? = Regex("(\\d+)\\s*号").find(machine)?.groupValues?.get(1)
    ?: Regex("\\d+").findAll(machine).lastOrNull()?.value

class LaundryWidgetSquareProvider : LaundryWidgetProvider() {
    internal override val widgetSize = WidgetSize.COMPACT
}

/** A building already supplied by the platform must not be repeated in a task title. */
internal fun widgetTaskName(building: String?, machine: String): String {
    val original = LaundryWidgetState.safeLabel(machine)
    // The shared projection can append the upstream name after a known slot.
    // Keep the bound position in the small card; raw names remain in the snapshot.
    val slotName = original.substringBefore(" · ")
    val name = if (original.contains(" · ") && Regex("^[\\p{L}\\p{N}-]+\\s*号(?:洗衣机|烘干机)$").matches(slotName))
        slotName.replace(Regex("\\s+号"), "号") else original
    val location = building?.let(LaundryWidgetState::safeLabel).orEmpty()
    if (location.isBlank() || name.isBlank() || name.startsWith(location, ignoreCase = true)) return name
    val separator = if (location.last().code < 128 && location.last().isLetterOrDigit()
        && name.first().code < 128 && name.first().isLetterOrDigit()) " " else ""
    return LaundryWidgetState.safeLabel(location + separator + name)
}

private fun widgetTime(context: Context, timestamp: Long): String =
    SimpleDateFormat("HH:mm", context.resources.configuration.locales[0]).format(Date(timestamp))
