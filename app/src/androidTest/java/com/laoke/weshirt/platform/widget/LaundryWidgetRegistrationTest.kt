package com.laoke.weshirt.platform.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Tests the installed picker metadata and component routing, not just enum names. */
@RunWith(AndroidJUnit4::class)
class LaundryWidgetRegistrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installedPickerExposesExactlyTheThreeFixedShapes() {
        val manager = AppWidgetManager.getInstance(context)
        val installed = manager.installedProviders.filter { it.provider.packageName == context.packageName }
        val expected = LaundryWidgetRegistry.entries.map { ComponentName(context, it.providerClass) }.toSet()
        assertEquals(expected, installed.map { it.provider }.toSet())
        assertEquals(3, installed.size)
        val receiverNames = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers.orEmpty().map { it.name }
        assertFalse(receiverNames.any { it.endsWith("LaundryWidgetExpandedProvider") || it.endsWith("LaundryWidgetLargeProvider") })
        assertEquals(listOf(2 to 1, 2 to 2, 4 to 2),
            LaundryWidgetRegistry.entries.map { it.create().widgetSize.let { size -> size.columns to size.rows } })

        LaundryWidgetRegistry.entries.forEach { entry ->
            val provider = entry.create()
            assertEquals(entry.providerClass, provider.javaClass)
            val component = ComponentName(context, entry.providerClass)
            val info = installed.single { it.provider == component }
            assertEquals(AppWidgetProviderInfo.RESIZE_NONE, info.resizeMode)
            assertEquals(0, info.updatePeriodMillis)
            assertEquals(expectedLayout(provider.widgetSize), info.initialLayout)
            if (Build.VERSION.SDK_INT >= 31) {
                assertEquals(provider.widgetSize.columns, info.targetCellWidth)
                assertEquals(provider.widgetSize.rows, info.targetCellHeight)
                if (provider.widgetSize == WidgetSize.COMPACT) assertEquals(R.layout.laundry_widget_square_preview, info.previewLayout)
            }
            assertMetadata(component, provider.widgetSize)
        }
        assertEquals("The existing component stays 4×2 for installed-widget upgrades", WidgetSize.WIDE, LaundryWidgetProvider().widgetSize)
    }

    @Suppress("DEPRECATION")
    private fun assertMetadata(component: ComponentName, size: WidgetSize) {
        val receiver = context.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
        assertFalse("Widget receivers must stay private", receiver.exported)
        assertTrue(receiver.loadLabel(context.packageManager).contains("${size.columns}×${size.rows}"))
        val parser = requireNotNull(receiver.loadXmlMetaData(context.packageManager, AppWidgetManager.META_DATA_APPWIDGET_PROVIDER))
        parser.use {
            while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) parser.next()
            val namespace = "http://schemas.android.com/apk/res/android"
            assertEquals("appwidget-provider", parser.name)
            assertEquals(size.columns, parser.getAttributeIntValue(namespace, "targetCellWidth", -1))
            assertEquals(size.rows, parser.getAttributeIntValue(namespace, "targetCellHeight", -1))
            assertEquals(AppWidgetProviderInfo.RESIZE_NONE, parser.getAttributeIntValue(namespace, "resizeMode", -1))
            val width = parser.getAttributeValue(namespace, "minWidth").removeSuffix("dip").removeSuffix("dp").toFloat()
            val height = parser.getAttributeValue(namespace, "minHeight").removeSuffix("dip").removeSuffix("dp").toFloat()
            assertEquals(when (size) { WidgetSize.MINI -> 128f; WidgetSize.COMPACT -> 120f; WidgetSize.WIDE -> 250f }, width, 0f)
            assertEquals(when (size) { WidgetSize.MINI -> 50f; WidgetSize.COMPACT -> 120f; WidgetSize.WIDE -> 120f }, height, 0f)
        }
    }

    @Test fun removingOneShapeKeepsOtherShapesInTheRefreshLifecycle() {
        val entries = LaundryWidgetRegistry.entries
        val idsByComponent = entries.mapIndexed { index, entry -> ComponentName(context, entry.providerClass) to intArrayOf(920 + index) }.toMap()
        fun remaining(deleted: IntArray = intArrayOf()) = LaundryWidgetRegistry.remainingIds(context, deleted) {
            idsByComponent[it] ?: intArrayOf()
        }
        assertArrayEquals(intArrayOf(920, 921, 922), remaining())
        assertArrayEquals(intArrayOf(921, 922), remaining(intArrayOf(920)))
        assertArrayEquals(intArrayOf(922), remaining(intArrayOf(920, 921)))
        assertTrue(remaining(intArrayOf(920, 921, 922)).isEmpty())
        entries.forEach { surviving ->
            val component = ComponentName(context, surviving.providerClass)
            val onlyOne = LaundryWidgetRegistry.remainingIds(context) { if (it == component) intArrayOf(930) else intArrayOf() }
            assertArrayEquals("Any surviving size keeps shared work alive", intArrayOf(930), onlyOne)
        }
        assertTrue(LaundryWidgetRegistry.remainingIds(context) { intArrayOf() }.isEmpty())
    }

    private fun expectedLayout(size: WidgetSize) = when (size) {
        WidgetSize.MINI -> R.layout.laundry_widget_mini
        WidgetSize.COMPACT -> R.layout.laundry_widget
        WidgetSize.WIDE -> R.layout.laundry_widget_wide
    }
}
