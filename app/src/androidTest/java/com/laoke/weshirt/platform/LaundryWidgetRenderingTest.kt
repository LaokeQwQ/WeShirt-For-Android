package com.laoke.weshirt.platform

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.widget.LaundryWidgetProvider
import com.laoke.weshirt.platform.widget.LaundryWidgetSnapshot
import com.laoke.weshirt.platform.widget.WidgetSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LaundryWidgetRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun responsiveCardsInflateWithRealRemoteViewsInBothThemesAndLanguages() {
        instrumentation.runOnMainSync {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                    val config = Configuration(instrumentation.targetContext.resources.configuration)
                    config.setLocale(locale)
                    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                    val context = instrumentation.targetContext.createConfigurationContext(config)
                    val now = System.currentTimeMillis()
                    for (size in WidgetSize.entries) {
                        val snapshot = LaundryWidgetSnapshot("Test building", 2, 3, 1, now, "Washer 01", now + 600_000)
                        val view = LaundryWidgetProvider().render(context, 900, snapshot, size, now)
                            .apply(context, FrameLayout(context))
                        assertEquals("Test building", view.findViewById<TextView>(R.id.widget_building).text.toString())
                        assertNotNull(view.contentDescription)
                        assertFalse("A countdown can run negative when the host is not refreshed", containsChronometer(view))
                        if (size != WidgetSize.COMPACT) {
                            assertEquals("Washer 01", view.findViewById<TextView>(R.id.widget_task).text.toString())
                            val expected = context.getString(R.string.widget_expected_at,
                                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now + 600_000)))
                            assertEquals(expected, view.findViewById<TextView>(R.id.widget_task_eta).text.toString())
                        }
                    }
                }
            }
        }
    }

    @Test fun expiredOrOutdatedEstimatesDoNotClaimCompletionAndInvalidDataHidesCounts() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val now = System.currentTimeMillis()
            val snapshot = LaundryWidgetSnapshot("Test building", 2, 3, 1, now, "Washer 01", now)
            val provider = LaundryWidgetProvider()
            val expired = provider.render(context, 901, snapshot, WidgetSize.WIDE, now).apply(context, FrameLayout(context))
            assertEquals(context.getString(R.string.widget_awaiting), expired.findViewById<TextView>(R.id.widget_task_eta).text.toString())
            val stale = provider.render(context, 901, snapshot.copy(updatedAtMillis = now - 600_001), WidgetSize.WIDE, now)
                .apply(context, FrameLayout(context))
            assertEquals(context.getString(R.string.widget_unverified), stale.findViewById<TextView>(R.id.widget_task_eta).text.toString())
            val invalid = provider.render(context, 901, snapshot.copy(updatedAtMillis = 0), WidgetSize.WIDE, now)
                .apply(context, FrameLayout(context))
            assertEquals("—", invalid.findViewById<TextView>(R.id.widget_primary).text.toString())
        }
    }

    private fun containsChronometer(view: View): Boolean = view is Chronometer ||
        view is ViewGroup && (0 until view.childCount).any { containsChronometer(view.getChildAt(it)) }
}
