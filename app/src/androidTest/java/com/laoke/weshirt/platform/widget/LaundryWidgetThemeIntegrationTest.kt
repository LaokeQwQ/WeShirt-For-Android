package com.laoke.weshirt.platform.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.R
import com.laoke.weshirt.ui.resolveWeShirtColorScheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real RemoteViews must follow the app preference rather than the launcher's own appearance. */
@RunWith(AndroidJUnit4::class)
class LaundryWidgetThemeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val prefs get() = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private var oldAppearance: String? = null
    private var oldPalette: String? = null

    @Before fun rememberAppearance() {
        oldAppearance = prefs.getString("appearance", null)
        oldPalette = prefs.getString("palette", null)
    }

    @After fun restoreAppearance() {
        prefs.edit().apply {
            if (oldAppearance == null) remove("appearance") else putString("appearance", oldAppearance)
            if (oldPalette == null) remove("palette") else putString("palette", oldPalette)
        }.commit()
    }

    private fun host(dark: Boolean): Context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
        fontScale = 1f
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    })

    private fun select(appearance: String, palette: String) {
        assertTrue(prefs.edit().putString("appearance", appearance).putString("palette", palette).commit())
    }

    private fun running(now: Long) = LaundryWidgetSnapshot("示例楼", 1, 1, 0, now,
        "Washer 01", now + 600_000L, taskStartedAtMillis = now - 600_000L)

    @Test fun sharedResolverUsesTheExactAppPrimaryAndSafeDefaults() {
        for (dark in listOf(false, true)) for (appearance in listOf("system", "light", "dark")) {
            for (palette in listOf("blue", "green", "purple", "dynamic")) {
                select(appearance, palette)
                val context = host(dark)
                val resolved = LaundryWidgetTheme.resolve(context)
                val appScheme = resolveWeShirtColorScheme(context, appearance, palette)
                assertEquals(appScheme.primary.toArgb(), resolved.accent)
                assertEquals(appScheme.background.toArgb(), resolved.surface)
                assertEquals(appearance == "dark" || appearance == "system" && dark, resolved.dark)
            }
        }
        select("unexpected", "unexpected")
        assertTrue(LaundryWidgetTheme.followsSystem(app))
        assertEquals(resolveWeShirtColorScheme(host(false), "system", "blue").primary.toArgb(), LaundryWidgetTheme.resolve(host(false)).accent)
        assertTrue(LaundryWidgetTheme.resolve(host(true)).dark)
    }

    @Test fun committedPreferenceIsVisibleToTheImmediateLocalRedraw() {
        select("light", "blue")
        var redraws = 0
        val redraw: (Context) -> Unit = { context ->
            redraws++
            assertEquals("dark", context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("appearance", null))
            assertTrue(LaundryWidgetTheme.resolve(context).dark)
        }
        assertEquals("dark", LaundryWidgetTheme.savePreference(app, "appearance", "dark", redraw))
        assertEquals(1, redraws)
        assertEquals("purple", LaundryWidgetTheme.savePreference(app, "palette", "purple") { context ->
            redraws++
            assertEquals("purple", context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("palette", null))
            assertEquals(resolveWeShirtColorScheme(context, "dark", "purple").primary.toArgb(), LaundryWidgetTheme.resolve(context).accent)
        })
        assertEquals(2, redraws)
        assertNull(LaundryWidgetTheme.savePreference(app, "quality", "AUTO") { fail("An unrelated setting must not redraw widgets") })
    }

    @Test fun forcedLightAndDarkOverrideTheHostForArtworkTextAndProgress() {
        instrumentation.runOnMainSync {
            val now = System.currentTimeMillis()
            for (appearance in listOf("light", "dark")) for (palette in listOf("blue", "green", "purple", "dynamic")) {
                select(appearance, palette)
                for (hostDark in listOf(false, true)) {
                    val context = host(hostDark)
                    val expected = LaundryWidgetTheme.resolve(context)
                    val view = LaundryWidgetProvider().render(context, 940, running(now), WidgetSize.WIDE, now)
                        .apply(context, FrameLayout(context))
                    assertTheme(view, expected)
                    assertTrue("Explicit appearance must ignore the host night mode", expected.dark == (appearance == "dark"))
                    if (hostDark != expected.dark) {
                        val card = LaundryWidgetProvider().render(context, 942, running(now), WidgetSize.WIDE, now)
                            .apply(context, FrameLayout(context))
                        capture(card, 320, 140, "native-widget-theme-$palette-$appearance-host-opposite")
                    }
                }
            }
        }
    }

    @Test fun oneSystemRemoteViewsChoosesDayAndNightWithoutRebuildingAppData() {
        if (Build.VERSION.SDK_INT < 31) return
        instrumentation.runOnMainSync {
            select("system", "green")
            val day = host(false)
            val night = host(true)
            val now = System.currentTimeMillis()
            // This same payload already includes both themes. No process callback or API request runs between applies.
            val remote = LaundryWidgetProvider().render(day, 941, running(now), WidgetSize.WIDE, now)
            val dayView = remote.apply(day, FrameLayout(day))
            val nightView = remote.apply(night, FrameLayout(night))
            assertTheme(dayView, LaundryWidgetTheme.resolve(day))
            assertTheme(nightView, LaundryWidgetTheme.resolve(night))
            remote.reapply(night, nightView)
            assertTheme(nightView, LaundryWidgetTheme.resolve(night))
            remote.reapply(day, dayView)
            assertTheme(dayView, LaundryWidgetTheme.resolve(day))
        }
    }

    private fun assertTheme(view: View, colors: WidgetArtworkPalette) {
        assertEquals(colors.primaryText, view.findViewById<TextView>(R.id.widget_task).currentTextColor)
        assertEquals(colors.secondaryText, view.findViewById<TextView>(R.id.widget_updated).currentTextColor)
        assertEquals(colors.accent, view.findViewById<TextView>(R.id.widget_task_eta).currentTextColor)
        assertBitmap(LaundryWidgetArtwork.background(colors, 320, 140), bitmap(view, R.id.widget_backdrop))
        assertBitmap(LaundryWidgetArtwork.shell(colors), bitmap(view, R.id.widget_machine_shell))
        assertBitmap(LaundryWidgetArtwork.ring(colors, 50), bitmap(view, R.id.widget_machine_ring))
        assertBitmap(LaundryWidgetArtwork.refresh(colors), bitmap(view, R.id.widget_refresh))
        val flipper = view.findViewById<ViewFlipper>(R.id.widget_drum_active)
        assertNotNull(flipper)
        assertEquals(3, flipper.childCount)
        for ((frame, id) in listOf(R.id.widget_drum_frame_0, R.id.widget_drum_frame_1, R.id.widget_drum_frame_2).withIndex()) {
            assertBitmap(LaundryWidgetArtwork.drum(colors, frame), bitmap(view, id))
        }
        assertBitmap(LaundryWidgetArtwork.dial(colors, 50), bitmap(view, R.id.widget_water_ring))
        val water = view.findViewById<ViewFlipper>(R.id.widget_water_active)
        assertNotNull(water)
        assertEquals(3, water.childCount)
        for ((frame, id) in listOf(R.id.widget_water_frame_0, R.id.widget_water_frame_1, R.id.widget_water_frame_2).withIndex()) {
            assertBitmap(LaundryWidgetArtwork.water(colors, 50, frame), bitmap(view, id))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(colors.accent, view.findViewById<ProgressBar>(R.id.widget_progress).progressTintList?.defaultColor)
            assertEquals(colors.outline, view.findViewById<ProgressBar>(R.id.widget_progress).progressBackgroundTintList?.defaultColor)
            assertEquals(colors.accent, view.findViewById<ProgressBar>(R.id.widget_activity).indeterminateTintList?.defaultColor)
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_progress_track).visibility)
        } else {
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_progress).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_progress_track).visibility)
            assertBitmap(LaundryWidgetArtwork.track(colors, 50), bitmap(view, R.id.widget_progress_track))
        }
    }

    private fun bitmap(view: View, id: Int): Bitmap = (view.findViewById<ImageView>(id).drawable as BitmapDrawable).bitmap
    private fun capture(view: View, widthDp: Int, heightDp: Int, name: String) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density + .5f).toInt()
        val height = (heightDp * density + .5f).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        for (id in listOf(R.id.widget_task, R.id.widget_task_eta, R.id.widget_updated)) {
            val label = view.findViewById<TextView>(id)
            assertTrue(label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
            for (line in 0 until label.layout.lineCount) assertEquals(0, label.layout.getEllipsisCount(line))
        }
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(image)); image.writeToTestStorage(name) } finally { image.recycle() }
    }
    private fun assertBitmap(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertTrue("Artwork must use the selected app palette", expected.sameAs(actual))
    }
}
