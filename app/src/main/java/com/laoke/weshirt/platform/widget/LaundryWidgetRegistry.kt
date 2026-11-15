package com.laoke.weshirt.platform.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** All picker entries share account data and refresh work, but keep their own layouts. */
internal object LaundryWidgetRegistry {
    internal data class Entry(
        val providerClass: Class<out LaundryWidgetProvider>,
        val create: () -> LaundryWidgetProvider,
    )

    val entries = listOf(
        Entry(LaundryWidgetMiniProvider::class.java, ::LaundryWidgetMiniProvider),
        Entry(LaundryWidgetSquareProvider::class.java, ::LaundryWidgetSquareProvider),
        Entry(LaundryWidgetProvider::class.java, ::LaundryWidgetProvider),
        Entry(LaundryWidgetExpandedProvider::class.java, ::LaundryWidgetExpandedProvider),
        Entry(LaundryWidgetLargeProvider::class.java, ::LaundryWidgetLargeProvider),
    )

    /** A provider's disabled callback does not imply that the other sizes are absent. */
    fun remainingIds(
        context: Context,
        deletedIds: IntArray = intArrayOf(),
        readIds: (ComponentName) -> IntArray = AppWidgetManager.getInstance(context)::getAppWidgetIds,
    ): IntArray = entries.flatMap { readIds(ComponentName(context, it.providerClass)).asIterable() }
        .filterNot { it in deletedIds }.distinct().toIntArray()

    fun refresh(context: Context, manager: AppWidgetManager = AppWidgetManager.getInstance(context)) {
        entries.forEach { entry ->
            val ids = manager.getAppWidgetIds(ComponentName(context, entry.providerClass))
            if (ids.isNotEmpty()) entry.create().onUpdate(context, manager, ids)
        }
    }
}
