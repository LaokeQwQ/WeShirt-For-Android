package com.laoke.weshirt.platform.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.NativeNavigation
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/** The original component remains the 4×2 entry so installed widgets keep working. */
open class LaundryWidgetProvider : AppWidgetProvider() {
    internal open val widgetSize: WidgetSize = WidgetSize.WIDE

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        redraw(context, manager, appWidgetIds)
        LaundryWidgetRefresh.scheduleTicks(context)
        val operation = LaundryWidgetRefresh.reconcile(context) ?: return
        val pending = goAsync()
        operation.result.addListener({ pending?.finish() }, Runnable::run)
    }

    internal fun redraw(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        LaundryWidgetTheme.observe(context)
        val snapshot = LaundryWidgetStore.read(context)
        for (id in appWidgetIds) {
            val options = if (widgetSize == WidgetSize.MINI) manager.getAppWidgetOptions(id) else Bundle.EMPTY
            manager.updateAppWidget(id, renderForHost(context, id, snapshot, System.currentTimeMillis(), options))
        }
    }

    override fun onDisabled(context: Context) {
        if (LaundryWidgetRegistry.remainingIds(context).isEmpty()) LaundryWidgetRefresh.cancel(context)
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        if (LaundryWidgetRegistry.remainingIds(context, appWidgetIds).isEmpty()) LaundryWidgetRefresh.cancel(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        manager.updateAppWidget(appWidgetId,
            renderForHost(context, appWidgetId, LaundryWidgetStore.read(context), System.currentTimeMillis(), newOptions))
    }

    internal fun renderForHost(context: Context, id: Int, snapshot: LaundryWidgetSnapshot?, now: Long, options: Bundle): RemoteViews {
        if (widgetSize != WidgetSize.MINI) return render(context, id, snapshot, widgetSize, now)
        fun mini(width: Int, height: Int) =
            render(context, id, snapshot, WidgetSize.MINI, now, MiniWidgetLayout.fit(width, height))
        if (Build.VERSION.SDK_INT >= 31) {
            val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                ?.filter { it.width in 64f..640f && it.height in 32f..320f }
                ?.distinct().orEmpty()
            if (sizes.size == 1) return mini(sizes.first().width.roundToInt(), sizes.first().height.roundToInt())
            // Portrait, landscape and folded/unfolded sizes may all be supplied.
            // Bound the number of themed bitmaps carried in a single update.
            if (sizes.size in 2..4) return RemoteViews(sizes.associateWith { mini(it.width.roundToInt(), it.height.roundToInt()) })
        }
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 }
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 }
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }
        if (minWidth == null && maxWidth == null && minHeight == null && maxHeight == null)
            return render(context, id, snapshot, WidgetSize.MINI, now)
        val portraitWidth = minWidth ?: maxWidth ?: 128
        val portraitHeight = maxHeight ?: minHeight ?: 76
        val landscapeWidth = maxWidth ?: portraitWidth
        val landscapeHeight = minHeight ?: portraitHeight
        val portrait = mini(portraitWidth, portraitHeight)
        if (landscapeWidth == portraitWidth && landscapeHeight == portraitHeight) return portrait
        return RemoteViews(mini(landscapeWidth, landscapeHeight), portrait)
    }

    internal fun render(
        context: Context, id: Int, snapshot: LaundryWidgetSnapshot?, size: WidgetSize, now: Long,
        mini: MiniWidgetLayout? = null,
    ): RemoteViews {
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
        val machine = snapshot?.taskMachineName?.let(LaundryWidgetState::safeLabel)?.takeIf(String::isNotBlank)
        val title = machine?.let { widgetTaskName(snapshot?.buildingName, it) } ?: context.getString(when {
            snapshot == null && !hasSession -> R.string.widget_sign_in
            taskState == WidgetTaskState.NONE && snapshot != null -> R.string.widget_idle
            else -> R.string.widget_pending
        })
        val taskStatus = when (taskState) {
            WidgetTaskState.NONE -> context.getString(when {
                refreshError != null -> R.string.widget_refresh_failed
                snapshot == null && hasSession -> R.string.widget_waiting_for_sync
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
        val visibleTitle = if (size != WidgetSize.MINI) title else when {
            machine != null -> title
            snapshot == null && !hasSession -> context.getString(R.string.widget_mini_sign_in)
            taskState != WidgetTaskState.NONE || snapshot == null -> context.getString(R.string.widget_mini_unverified)
            else -> title
        }
        views.setTextViewText(R.id.widget_task, visibleTitle)
        val visibleStatus = when {
            refreshError != null -> context.getString(R.string.widget_refresh_failed)
            size == WidgetSize.MINI -> miniStatus(context, taskState, taskStatus, snapshot, hasSession)
            else -> taskStatus
        }
        views.setTextViewText(R.id.widget_task_eta, visibleStatus)
        val accessibleProgress = context.getString(R.string.widget_progress_percent, progress ?: 0)
            .takeIf { progress != null } ?: context.getString(R.string.widget_progress_unknown)
        views.setContentDescription(R.id.widget_root, "$building. $description. $accessibleProgress" + refreshError?.let { ". $it" }.orEmpty())
        views.setOnClickPendingIntent(R.id.widget_building, board)
        views.setOnClickPendingIntent(R.id.widget_root, if (machine == null) board else task)
        views.setOnClickPendingIntent(R.id.widget_task_section, task)
        if (size != WidgetSize.MINI) {
            views.setViewVisibility(R.id.widget_progress, if (progress != null && Build.VERSION.SDK_INT >= 31) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_progress_track, if (progress != null && Build.VERSION.SDK_INT < 31) View.VISIBLE else View.GONE)
            views.setProgressBar(R.id.widget_progress, 100, progress ?: 0, false)
        }
        views.setViewVisibility(R.id.widget_progress_label, View.GONE)
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
        if (mini != null) applyMiniLayout(context, views, mini)
        applyTheme(context, views, size, progress, mini)
        return views
    }

    private fun miniStatus(context: Context, state: WidgetTaskState, taskStatus: String, snapshot: LaundryWidgetSnapshot?, hasSession: Boolean): String = when (state) {
        WidgetTaskState.ESTIMATED -> taskStatus
        WidgetTaskState.AWAITING_CONFIRMATION -> context.getString(R.string.widget_mini_awaiting)
        WidgetTaskState.READY_FOR_PICKUP -> context.getString(R.string.widget_mini_ready)
        WidgetTaskState.UNVERIFIED -> context.getString(R.string.widget_mini_unverified)
        WidgetTaskState.NONE -> context.getString(when {
            snapshot == null && !hasSession -> R.string.widget_mini_sign_in_detail
            snapshot == null -> R.string.widget_waiting_for_sync
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

    private fun applyMiniLayout(context: Context, views: RemoteViews, layout: MiniWidgetLayout) {
        fun px(dp: Int) = (dp * context.resources.displayMetrics.density).roundToInt()
        val outsideY = px(layout.verticalInset)
        val outsideX = px(layout.horizontalInset)
        val horizontal = outsideX + px(layout.horizontalPadding)
        val vertical = outsideY + px(layout.verticalPadding)
        val labelInset = px(layout.labelInset)
        views.setViewPadding(R.id.widget_backdrop, outsideX, outsideY, outsideX, outsideY)
        views.setViewPadding(R.id.widget_content, horizontal, vertical, horizontal, vertical)
        views.setViewPadding(R.id.widget_task_section, 0, labelInset, 0, labelInset)
    }

    private fun applyTheme(context: Context, views: RemoteViews, size: WidgetSize, progress: Int?, mini: MiniWidgetLayout?) {
        val actions = ThemeActions(context, views)
        val dimensions = when (size) {
            WidgetSize.MINI -> (mini?.width ?: 180) to (mini?.cardHeight ?: 80)
            WidgetSize.COMPACT -> 180 to 180
            WidgetSize.WIDE -> 320 to 140
        }
        // Bitmaps and text colors override host-qualified resources, including an explicitly forced app theme.
        views.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT)
        actions.image(R.id.widget_backdrop, "background:${dimensions.first}:${dimensions.second}") { LaundryWidgetArtwork.background(it, dimensions.first, dimensions.second) }
        if (size == WidgetSize.MINI) {
            actions.image(R.id.widget_machine_ring, "dial:$progress") { LaundryWidgetArtwork.dial(it, progress) }
        } else {
            actions.image(R.id.widget_machine_shell, draw = LaundryWidgetArtwork::shell)
            actions.image(R.id.widget_drum_static, draw = LaundryWidgetArtwork::drum)
            if (Build.VERSION.SDK_INT >= 31) {
                actions.tint(R.id.widget_progress, "setProgressTintList") { it.accent }
                actions.tint(R.id.widget_progress, "setProgressBackgroundTintList") { it.outline }
            } else actions.image(R.id.widget_progress_track, "track:$progress") { LaundryWidgetArtwork.track(it, progress) }
        }
        actions.text(R.id.widget_building) { it.primaryText }
        actions.text(R.id.widget_task) { it.primaryText }
        actions.text(R.id.widget_task_eta) { it.secondaryText }
        actions.text(R.id.widget_progress_label) { it.secondaryText }
        actions.text(R.id.widget_task_note) { it.secondaryText }
    }

    companion object {
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
