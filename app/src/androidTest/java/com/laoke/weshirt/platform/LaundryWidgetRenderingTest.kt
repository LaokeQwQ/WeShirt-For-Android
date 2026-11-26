package com.laoke.weshirt.platform

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.MainActivity
import com.laoke.weshirt.R
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.platform.widget.LaundryWidgetProvider
import com.laoke.weshirt.platform.widget.LaundryWidgetMiniProvider
import com.laoke.weshirt.platform.widget.LaundryWidgetSnapshot
import com.laoke.weshirt.platform.widget.LaundryWidgetStore
import com.laoke.weshirt.platform.widget.WidgetSize
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** Exercises actual RemoteViews actions, native layout measurement and attached host animation. */
@RunWith(AndroidJUnit4::class)
class LaundryWidgetRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private var oldEndpoint: String? = null
    private var oldAppearance: String? = null
    private var oldPalette: String? = null
    private val fullCardSizes get() = WidgetSize.entries.filter { it != WidgetSize.MINI }

    @Before fun prepare() {
        val prefs = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        oldEndpoint = prefs.getString("endpoint", null)
        oldAppearance = prefs.getString("appearance", null)
        oldPalette = prefs.getString("palette", null)
        prefs.edit().putString("endpoint", "https://widget.invalid")
            .putString("appearance", "system").putString("palette", "blue").commit()
        SecureSession(app).clear()
        LaundryWidgetStore.clear(app)
    }

    @After fun cleanup() {
        SecureSession(app).clear()
        LaundryWidgetStore.clear(app)
        val edit = app.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
        if (oldEndpoint == null) edit.remove("endpoint") else edit.putString("endpoint", oldEndpoint)
        if (oldAppearance == null) edit.remove("appearance") else edit.putString("appearance", oldAppearance)
        if (oldPalette == null) edit.remove("palette") else edit.putString("palette", oldPalette)
        edit.commit()
    }

    private fun task(now: Long) = LaundryWidgetSnapshot("示例楼", 9, 0, 0, now,
        "1号洗衣机", now + 600_000, taskStartedAtMillis = now - 600_000)

    @Test fun miniCardsKeepShortTaskStatesReadableAndFullDetailsAccessible() {
        data class Case(val name: String, val snapshot: LaundryWidgetSnapshot?, val status: Int, val progress: Int?)
        val provider = LaundryWidgetMiniProvider()
        val now = System.currentTimeMillis()
        val cases = listOf(
            Case("guest", null, R.string.widget_mini_sign_in_detail, null),
            Case("idle", LaundryWidgetSnapshot("示例楼", 9, 0, 0, now), R.string.widget_mini_no_task, null),
            Case("running", task(now), R.string.widget_expected_finish, 50),
            Case("ten-percent", task(now).copy(taskStartedAtMillis = now - 100_000,
                taskEstimatedFinishAtMillis = now + 900_000), R.string.widget_expected_finish, 10),
            Case("awaiting", task(now).copy(taskEstimatedFinishAtMillis = now), R.string.widget_mini_awaiting, 100),
            Case("stale", task(now).copy(updatedAtMillis = now - 600_001), R.string.widget_mini_unverified, null),
            Case("ready", task(now).copy(taskCompletionConfirmed = true), R.string.widget_mini_ready, 100),
            Case("missing-start", task(now).copy(taskStartedAtMillis = null), R.string.widget_expected_finish, null),
            Case("missing-finish", task(now).copy(taskEstimatedFinishAtMillis = null), R.string.widget_mini_unverified, null),
        )
        instrumentation.runOnMainSync {
                for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                    for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                        for (font in listOf(1f, 1.4f)) {
                            val config = Configuration(app.resources.configuration).apply {
                                setLocale(locale)
                                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                                fontScale = font
                            }
                            val context = app.createConfigurationContext(config)
                            for ((width, height) in listOf(150 to 76, 180 to 80)) for (case in cases) {
                                val view = provider.render(context, 905, case.snapshot, WidgetSize.MINI, now)
                                    .apply(context, FrameLayout(context))
                                measure(view, width, height)
                                val label = "2x1 at ${width}x$height, ${locale.language}, night $night, font $font, ${case.name}"
                                captureView(view,
                                    "native-widget-2x1-${width}x$height-${locale.language}-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${if (font == 1.4f) "large-font" else "default-font"}-${case.name}")
                                for (id in listOf(R.id.widget_machine, R.id.widget_task_section, R.id.widget_task, R.id.widget_task_eta)) {
                                    val child = view.findViewById<View>(id)
                                    assertInside(view, child)
                                    assertTrue("Mini content must retain height ($label, id=$id)", child.height > 0)
                                }
                                val title = view.findViewById<TextView>(R.id.widget_task)
                                val eta = view.findViewById<TextView>(R.id.widget_task_eta)
                                val expectedTitle = when {
                                    case.snapshot == null -> context.getString(R.string.widget_mini_sign_in)
                                    case.snapshot.taskMachineName == null -> context.getString(R.string.widget_idle)
                                    else -> "示例楼1号洗衣机"
                                }
                                assertEquals("The mini title must identify the task ($label)", expectedTitle, title.text.toString())
                                val expectedStatus = if (case.status == R.string.widget_expected_finish)
                                    context.getString(case.status, SimpleDateFormat("HH:mm", locale).format(Date(requireNotNull(case.snapshot?.taskEstimatedFinishAtMillis)))) else context.getString(case.status)
                                assertEquals("The mini status must preserve task semantics ($label)", expectedStatus, eta.text.toString())
                                assertReadable(title, label)
                                assertReadable(eta, label)
                                assertTextInset(view, title, label)
                                assertTextInset(view, eta, label)
                                val machine = rect(view, view.findViewById(R.id.widget_machine))
                                val section = rect(view, view.findViewById(R.id.widget_task_section))
                                assertTrue("Mini dial and task cannot overlap ($label)", machine.right <= section.left)
                                assertNoRefreshControls(view)
                                assertTrue("Mini task text should be vertically centered with the dial ($label)",
                                    kotlin.math.abs((rect(view, title).top + rect(view, eta).bottom) / 2 - machine.centerY()) <= dp(context, 4))
                                assertTrue("Mini task labels cannot overlap ($label)", rect(view, title).bottom <= rect(view, eta).top)
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_building).visibility)
                                val description = view.contentDescription.toString()
                                val building = case.snapshot?.buildingName ?: context.getString(R.string.widget_brand)
                                assertTrue("Hidden mini building must remain accessible ($label)", description.contains(building))
                                case.snapshot?.taskMachineName?.let { fullName ->
                                    assertTrue("The full machine name remains accessible ($label)", description.contains(fullName))
                                }
                                assertNull("The mini dial must not install a washer animation ($label)", view.findViewById<ViewFlipper>(R.id.widget_drum_active))
                                val waterRunning = case.name in listOf("running", "ten-percent")
                                assertWaterState(view, waterRunning, label)
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_drum_holder).visibility)
                                val dial = view.findViewById<ImageView>(R.id.widget_machine_ring)
                                assertEquals(if (waterRunning) View.GONE else View.VISIBLE, dial.visibility)
                                assertNotNull("Mini cards must render their progress dial ($label)", dial.drawable)
                                val visibleDial = if (waterRunning) view.findViewById<View>(R.id.widget_water_holder) else dial
                                assertInside(view, visibleDial)
                                val minimumDial = dp(context, if (width == 150) 40 else 50)
                                assertTrue("Mini progress must be recognizable at each host size ($label)",
                                    minOf(visibleDial.width, visibleDial.height) >= minimumDial)
                                val progress = view.findViewById<ProgressBar>(R.id.widget_progress)
                                assertEquals("The mini dial must not duplicate a determinate progress bar ($label)", View.GONE, progress.visibility)
                                if (case.progress != null) assertEquals(case.progress, progress.progress)
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_progress_track).visibility)
                                val activity = view.findViewById<View>(R.id.widget_activity)
                                assertEquals("Mini activity supplies movement only when the running task has no estimate ($label)",
                                    if (case.name == "missing-start") View.VISIBLE else View.GONE, activity.visibility)
                                if (activity.visibility == View.VISIBLE) assertInside(view, activity)
                                assertFalse("Mini cards must not use a host countdown ($label)", containsChronometer(view))
                                assertFalse("Mini cards cannot show a zero or negative estimate ($label)",
                                    eta.text.toString().contains("0 分钟") || eta.text.toString().contains("0 min") || eta.text.toString().contains("-1"))
                            }
                        }
                    }
                }
        }
    }

    @Test fun miniFollowsHostSizesAndKeepsItsCardHorizontalAcrossLaunchers() {
        val provider = LaundryWidgetMiniProvider()
        val component = ComponentName(app, LaundryWidgetMiniProvider::class.java)
        val providerInfo = AppWidgetManager.getInstance(app).installedProviders.single { it.provider == component }
        val now = System.currentTimeMillis()
        instrumentation.runOnMainSync {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                    for (font in listOf(1f, 1.4f)) {
                        val configuration = Configuration(app.resources.configuration).apply {
                            setLocale(locale)
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                            fontScale = font
                            orientation = Configuration.ORIENTATION_PORTRAIT
                        }
                        val context = app.createConfigurationContext(configuration)
                        val draw = { width: Int, height: Int, options: Bundle, name: String ->
                            val remote = provider.renderForHost(context, 905, task(now), now, options)
                            val view = if (name == "legacy") remote.apply(context, FrameLayout(context)) else {
                                val host = AppWidgetHostView(context).apply {
                                    setAppWidget(905, providerInfo)
                                    // Report inner cell dimensions, as the launcher does after its own padding.
                                    setPadding(0, 0, 0, 0)
                                }
                                measure(host, width, height)
                                host.updateAppWidget(remote)
                                measure(host, width, height)
                                requireNotNull(host.findViewById<View>(R.id.widget_root))
                            }
                            assertResponsiveMini(view, width, height, name)
                            captureView(view, "native-widget-2x1-host-$name-${width}x$height-${locale.language}-" +
                                "${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${if (font == 1.4f) "large-font" else "default-font"}")
                        }
                        val legacy = Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 128)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 50)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 108)
                        }
                        draw(128, 108, legacy, "legacy")
                        val landscape = context.createConfigurationContext(Configuration(configuration).apply {
                            orientation = Configuration.ORIENTATION_LANDSCAPE
                        })
                        val landscapeView = provider.renderForHost(landscape, 905, task(now), now, legacy)
                            .apply(landscape, FrameLayout(landscape))
                        assertResponsiveMini(landscapeView, 260, 50, "flat")
                        assertResponsiveMini(provider.renderForHost(context, 905, task(now), now, Bundle.EMPTY)
                            .apply(context, FrameLayout(context)), 150, 52, "picker fallback")
                        if (Build.VERSION.SDK_INT < 31) continue
                        val host = Bundle().apply {
                            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                                arrayListOf(SizeF(132f, 50f), SizeF(154f, 104f), SizeF(260f, 52f)))
                        }
                        draw(132, 50, host, "narrow")
                        draw(154, 104, host, "tall")
                        draw(260, 52, host, "flat")
                    }
                }
            }
        }
    }

    private fun assertResponsiveMini(view: View, width: Int, height: Int, name: String) {
        measure(view, width, height)
        val label = "$name ${width}x$height, ${view.resources.configuration.locales[0]}, font ${view.resources.configuration.fontScale}"
        val backdrop = view.findViewById<ImageView>(R.id.widget_backdrop)
        val cardWidth = view.width - backdrop.paddingLeft - backdrop.paddingRight
        val cardHeight = view.height - backdrop.paddingTop - backdrop.paddingBottom
        assertTrue("Host cells retain a horizontal card ($label)", cardWidth > cardHeight * 1.65)
        assertEquals(backdrop.paddingLeft, backdrop.paddingRight)
        assertEquals(backdrop.paddingTop, backdrop.paddingBottom)
        if (name == "tall" || name == "legacy") assertTrue(backdrop.paddingTop >= dp(view.context, 10))
        if (name == "flat") assertTrue(backdrop.paddingLeft >= dp(view.context, 10))
        val card = Rect(backdrop.paddingLeft, backdrop.paddingTop,
            view.width - backdrop.paddingRight, view.height - backdrop.paddingBottom)
        val machine = rect(view, view.findViewById(R.id.widget_machine))
        val title = view.findViewById<TextView>(R.id.widget_task)
        val eta = view.findViewById<TextView>(R.id.widget_task_eta)
        for (text in listOf(title, eta)) {
            assertInside(view, text)
            assertReadable(text, label)
            assertTextInset(view, text, label, card)
        }
        assertTrue("The dial stays to the left of both labels ($label)", machine.right <= rect(view, title).left)
        assertTrue("Labels must stay on separate rows ($label)", rect(view, title).bottom <= rect(view, eta).top)
        assertTrue("The dial must remain readable ($label)", minOf(machine.width(), machine.height()) >= dp(view.context, 34))
        assertNoRefreshControls(view)
        assertWaterState(view, true, label)
    }

    @Test fun responsiveTaskCardsApplyInBothThemesLanguagesAndLargeFontsWithoutCountSummary() {
        instrumentation.runOnMainSync {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                    for (font in listOf(1f, 1.4f)) {
                        val config = Configuration(app.resources.configuration).apply {
                            setLocale(locale)
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                            fontScale = font
                        }
                        val context = app.createConfigurationContext(config)
                        val now = System.currentTimeMillis()
                        for (size in fullCardSizes) {
                            val dimensions = when (size) {
                                WidgetSize.MINI -> error("Mini cards have a dedicated layout matrix")
                                WidgetSize.COMPACT -> listOf(150 to 150, 180 to 180)
                                WidgetSize.WIDE -> listOf(250 to 120, 320 to 140)
                            }
                            for ((width, height) in dimensions) {
                                val view = LaundryWidgetProvider().render(context, 900, task(now), size, now)
                                    .apply(context, FrameLayout(context))
                                measure(view, width, height)
                                // Save before assertions so a failing size is still inspectable.
                                captureView(view,
                                    "native-widget-${size.columns}x${size.rows}-${width}x$height-${locale.language}-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${if (font == 1.4f) "large-font" else "default-font"}")
                                assertEquals("示例楼", view.findViewById<TextView>(R.id.widget_building).text.toString())
                                assertEquals("示例楼1号洗衣机", view.findViewById<TextView>(R.id.widget_task).text.toString())
                                assertEquals(context.getString(R.string.widget_expected_finish, SimpleDateFormat("HH:mm", locale).format(Date(now + 600_000))), view.findViewById<TextView>(R.id.widget_task_eta).text.toString())
                                assertEquals(50, view.findViewById<ProgressBar>(R.id.widget_progress).progress)
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_progress_label).visibility)
                                assertNoRefreshControls(view)
                                assertFalse("A host countdown must never turn negative", containsChronometer(view))
                                assertFalse("The widget describes the user's task, not an idle-machine count", allText(view).contains("台空闲"))
                                assertFalse("The widget does not show a second usage summary", allText(view).contains("使用中") || allText(view).contains("Unknown") || allText(view).contains("未知"))
                                for (id in listOf(R.id.widget_task_section, R.id.widget_machine, R.id.widget_task, R.id.widget_task_eta, R.id.widget_progress)) {
                                    assertInside(view, view.findViewById(id))
                                }
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_building).visibility)
                                for (id in listOf(R.id.widget_task, R.id.widget_task_eta)) {
                                    assertReadable(view.findViewById(id), "${size.columns}x${size.rows} at ${width}x$height, ${locale.language}, font $font")
                                    assertTextInset(view, view.findViewById(id), "${size.columns}x${size.rows} at ${width}x$height, ${locale.language}, font $font")
                                }
                                val section = rect(view, view.findViewById(R.id.widget_task_section))
                                val machine = rect(view, view.findViewById(R.id.widget_machine))
                                val title = rect(view, view.findViewById(R.id.widget_task))
                                val eta = rect(view, view.findViewById(R.id.widget_task_eta))
                                val progress = rect(view, view.findViewById(R.id.widget_progress))
                                val measuredCase = "${size.columns}x${size.rows} at ${width}x$height, ${locale.language}, night $night, font $font; machine=$machine, section=$section, title=$title, eta=$eta, progress=$progress"
                                assertTrue("The task area cannot collapse ($measuredCase)", section.height() > 0)
                                val illustration = artworkBounds(view, view.findViewById(R.id.widget_machine_shell))
                                val minimumArt = if (size == WidgetSize.COMPACT) {
                                    if (height == 150) 56 else 84
                                } else if (height == 120) 80 else 90
                                assertTrue("The visible machine must remain prominent, artwork=$illustration ($measuredCase)",
                                    illustration.height() >= dp(context, minimumArt))
                                if (size == WidgetSize.WIDE) {
                                    assertTrue("The wide washer must stay to the left of the task ($measuredCase)",
                                        machine.right <= minOf(section.left, title.left, eta.left, progress.left))
                                    assertTrue("The wide washer must remain prominent ($measuredCase)",
                                        machine.width() >= dp(context, 72) && machine.height() >= dp(context, if (height == 120) 80 else 100))
                                    assertTrue("The wide task information should be vertically centered ($measuredCase)",
                                        kotlin.math.abs((title.top + progress.bottom) / 2 - machine.centerY()) <= dp(context, 2))
                                }
                                assertTrue("The machine name and remaining time cannot overlap ($measuredCase)", title.bottom <= eta.top)
                                assertTrue("The remaining time must stay above progress ($measuredCase)", eta.bottom <= progress.top)
                                if (size != WidgetSize.WIDE) {
                                    assertTrue("The centered illustration must stay above its labels ($measuredCase)", machine.bottom <= title.top)
                                }
                                assertNoWater(view, measuredCase)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun minimumCardsKeepGuestIdleAndUnverifiedStatusReadableAtLargeFont() {
        instrumentation.runOnMainSync {
            val now = System.currentTimeMillis()
            val snapshots = listOf(null, LaundryWidgetSnapshot("示例楼", 9, 0, 0, now), task(now),
                task(now).copy(taskEstimatedFinishAtMillis = now),
                task(now).copy(taskCompletionConfirmed = true),
                task(now).copy(taskStartedAtMillis = null),
                task(now).copy(updatedAtMillis = now - 600_001),
                task(now).copy(taskEstimatedFinishAtMillis = null))
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                    for (font in listOf(1f, 1.4f)) {
                        val config = Configuration(app.resources.configuration).apply {
                            setLocale(locale); fontScale = font
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                        }
                        val context = app.createConfigurationContext(config)
                        for (size in fullCardSizes) for ((index, snapshot) in snapshots.withIndex()) {
                            val view = LaundryWidgetProvider().render(context, 904, snapshot, size, now)
                                .apply(context, FrameLayout(context))
                            assertStatusFitsMinimum(view, size)
                            val status = when (index) {
                                0 -> context.getString(R.string.widget_sign_in_detail)
                                1 -> context.getString(R.string.widget_no_task)
                                2, 5 -> context.getString(R.string.widget_expected_finish,
                                    SimpleDateFormat("HH:mm", locale).format(Date(now + 600_000)))
                                3 -> context.getString(R.string.widget_awaiting)
                                4 -> context.getString(R.string.widget_ready_for_pickup)
                                else -> context.getString(R.string.widget_unverified)
                            }
                            assertEquals("All shapes must preserve the task state, $size/$index/$locale/$night/$font",
                                status, view.findViewById<TextView>(R.id.widget_task_eta).text.toString())
                            assertNoWater(view, "$size status $index/$locale/$night/$font")
                        }
                    }
                }
            }
        }
    }

    @Test fun idleAwaitingConfirmedAndStaleTasksHaveNoFalseCountdownOrAnimation() {
        instrumentation.runOnMainSync {
            val now = System.currentTimeMillis()
            val provider = LaundryWidgetProvider()
            for (size in fullCardSizes) {
                val idle = provider.render(app, 901, LaundryWidgetSnapshot("示例楼", 9, 0, 0, now), size, now).apply(app, FrameLayout(app))
                assertEquals(app.getString(R.string.widget_idle), idle.findViewById<TextView>(R.id.widget_task).text.toString())
                assertNull(idle.findViewById<ViewFlipper>(R.id.widget_drum_active))
                assertEquals(View.GONE, idle.findViewById<View>(R.id.widget_activity).visibility)
                assertEquals(View.GONE, idle.findViewById<View>(R.id.widget_progress_label).visibility)
                val expired = task(now).copy(taskEstimatedFinishAtMillis = now)
                for (offset in listOf(0L, 60_000L)) {
                    val view = provider.render(app, 901, expired, size, now + offset).apply(app, FrameLayout(app))
                    assertEquals(app.getString(R.string.widget_awaiting), view.findViewById<TextView>(R.id.widget_task_eta).text.toString())
                    assertNull(view.findViewById<ViewFlipper>(R.id.widget_drum_active))
                    assertFalse(allText(view).contains("0 分钟") || allText(view).contains("0 min") || allText(view).contains("-1"))
                }
                val confirmed = provider.render(app, 901, expired.copy(taskCompletionConfirmed = true), size, now).apply(app, FrameLayout(app))
                assertEquals(app.getString(R.string.widget_ready_for_pickup), confirmed.findViewById<TextView>(R.id.widget_task_eta).text.toString())
                assertEquals(100, confirmed.findViewById<ProgressBar>(R.id.widget_progress).progress)
                val stale = provider.render(app, 901, task(now).copy(updatedAtMillis = now - 600_001), size, now).apply(app, FrameLayout(app))
                assertEquals(app.getString(R.string.widget_unverified), stale.findViewById<TextView>(R.id.widget_task_eta).text.toString())
                assertNull(stale.findViewById<ViewFlipper>(R.id.widget_drum_active))
                assertEquals(View.GONE, stale.findViewById<View>(R.id.widget_progress).visibility)
                val missingStart = provider.render(app, 901, task(now).copy(taskStartedAtMillis = null), size, now).apply(app, FrameLayout(app))
                assertEquals(View.GONE, missingStart.findViewById<View>(R.id.widget_progress).visibility)
                assertEquals(View.GONE, missingStart.findViewById<View>(R.id.widget_progress_label).visibility)
                assertNoWater(missingStart, "$size missing start")
            }
        }
    }

    @Test fun missingSnapshotDistinguishesSignedOutFromSignedInAndKeepsSafeRefreshError() {
        val provider = LaundryWidgetProvider()
        instrumentation.runOnMainSync {
            val guest = provider.render(app, 903, null, WidgetSize.WIDE, System.currentTimeMillis()).apply(app, FrameLayout(app))
            assertEquals(app.getString(R.string.widget_sign_in), guest.findViewById<TextView>(R.id.widget_task).text.toString())
        }
        SecureSession(app).save(JSONObject().put("origin", "https://widget.invalid").put("accessToken", "ma1.widget-render.fixture")
            .put("user", JSONObject().put("id", "widget-render")))
        val scope = requireNotNull(LaundryWidgetStore.captureScope(app))
        instrumentation.runOnMainSync {
            val signedIn = provider.render(app, 903, null, WidgetSize.WIDE, System.currentTimeMillis()).apply(app, FrameLayout(app))
            assertEquals(app.getString(R.string.widget_pending), signedIn.findViewById<TextView>(R.id.widget_task).text.toString())
            assertEquals(app.getString(R.string.widget_waiting_for_sync), signedIn.findViewById<TextView>(R.id.widget_task_eta).text.toString())
        }
        assertTrue(LaundryWidgetStore.setError(app, "HTTP 503 · event_widget_fixture", scope))
        instrumentation.runOnMainSync {
            val failed = provider.render(app, 903, null, WidgetSize.WIDE, System.currentTimeMillis()).apply(app, FrameLayout(app))
            assertEquals(app.getString(R.string.widget_refresh_failed), failed.findViewById<TextView>(R.id.widget_task_eta).text.toString())
            assertTrue(failed.contentDescription.toString().contains("event_widget_fixture"))
            assertFalse(allText(failed).contains(app.getString(R.string.widget_sign_in)))
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                val config = Configuration(app.resources.configuration).apply { setLocale(locale); fontScale = 1.4f }
                val context = app.createConfigurationContext(config)
                for (size in fullCardSizes) {
                    val error = provider.render(context, 903, null, size, System.currentTimeMillis()).apply(context, FrameLayout(context))
                    assertStatusFitsMinimum(error, size)
                }
            }
        }
    }

    @Test fun attachedHostActuallyFlipsOnlyRunningDrum() {
        // CI disables animator duration globally. Opt this animation test in,
        // then restore the emulator's setting even when an assertion fails.
        val durationScale = shell("settings get global animator_duration_scale")
        try {
            shell("settings put global animator_duration_scale 1")
            val scaleDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (!android.animation.ValueAnimator.areAnimatorsEnabled() && android.os.SystemClock.elapsedRealtime() < scaleDeadline)
                Thread.sleep(50)
            assertTrue("This test must opt into system animations before attaching the host", android.animation.ValueAnimator.areAnimatorsEnabled())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val provider = LaundryWidgetProvider()
                val now = System.currentTimeMillis()
                lateinit var root: View
                var firstFrame = -1
                scenario.onActivity { activity ->
                    val host = FrameLayout(activity)
                    activity.setContentView(host)
                    root = provider.render(activity, 902, task(now), WidgetSize.WIDE, now).apply(activity, host)
                    host.addView(root, FrameLayout.LayoutParams(dp(activity, 320), dp(activity, 140)))
                }
                instrumentation.waitForIdleSync()
                // ProgressBar starts its Animatable in drawTrack(), after attachment.
                // An idle message queue alone does not guarantee that the first host
                // frame has been laid out and drawn.
                val firstFrameDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var hostDrawn = false
                while (!hostDrawn && android.os.SystemClock.elapsedRealtime() < firstFrameDeadline) {
                    scenario.onActivity {
                        val overlay = root.findViewById<ProgressBar>(R.id.widget_activity)
                        hostDrawn = root.isLaidOut && root.width > 0 && root.height > 0 &&
                            overlay.isShown && isRunning(overlay.indeterminateDrawable)
                    }
                    if (!hostDrawn) Thread.sleep(100)
                }
                assertTrue("The host must draw its first running activity frame", hostDrawn)
                scenario.onActivity {
                    val flipper = root.findViewById<ViewFlipper>(R.id.widget_drum_active)
                    assertTrue(flipper.isShown)
                    assertTrue(flipper.isFlipping)
                    firstFrame = flipper.displayedChild
                    val activity = root.findViewById<ProgressBar>(R.id.widget_activity)
                    assertTrue(activity.isShown)
                    assertTrue("The host must animate the activity overlay", isRunning(activity.indeterminateDrawable))
                    val known = root.findViewById<ProgressBar>(R.id.widget_progress)
                    assertEquals(50, known.progress)
                    assertEquals("Activity is an overlay on the same progress track", rect(root, known), rect(root, activity))
                }
                // A real ViewFlipper timer, not a test-driven showNext(), advances the drum.
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var advanced = false
                while (!advanced && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                    scenario.onActivity { advanced = root.findViewById<ViewFlipper>(R.id.widget_drum_active).displayedChild != firstFrame }
                }
                assertTrue("The attached host must advance the drum on its own", advanced)
                scenario.onActivity {
                    assertNoRefreshControls(root)
                    captureView(root, "native-widget-attached-running")
                }
                scenario.onActivity { activity ->
                    provider.render(activity, 902, LaundryWidgetSnapshot("示例楼", 9, 0, 0, now), WidgetSize.WIDE, now).reapply(activity, root)
                    assertNull("Idle redraw detaches the timer rather than hiding a live flipper", root.findViewById<ViewFlipper>(R.id.widget_drum_active))
                    assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_drum_static).visibility)
                    assertEquals(View.GONE, root.findViewById<View>(R.id.widget_activity).visibility)
                    captureView(root, "native-widget-attached-idle")
                }
            }
        } finally {
            if (durationScale == "null") shell("settings delete global animator_duration_scale")
            else {
                require(durationScale.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
                shell("settings put global animator_duration_scale $durationScale")
            }
        }
    }

    @Test fun attachedAnimationsAdvanceAndOnlyMiniUsesWater() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val provider = LaundryWidgetProvider()
            for (size in WidgetSize.entries) {
                val now = System.currentTimeMillis()
                lateinit var root: View
                scenario.onActivity { activity ->
                    val host = FrameLayout(activity)
                    activity.setContentView(host)
                    root = provider.render(activity, 906, task(now), size, now).apply(activity, host)
                    val (width, height) = when (size) {
                        WidgetSize.MINI -> 180 to 80
                        WidgetSize.COMPACT -> 180 to 180
                        WidgetSize.WIDE -> 320 to 140
                    }
                    host.addView(root, FrameLayout.LayoutParams(dp(activity, width), dp(activity, height)))
                }
                instrumentation.waitForIdleSync()
                var first = -1
                scenario.onActivity {
                    val animation = root.findViewById<ViewFlipper>(if (size == WidgetSize.MINI) R.id.widget_water_active else R.id.widget_drum_active)
                    assertTrue("The $size host must run its native task timer", animation.isFlipping)
                    assertEquals(3, animation.childCount)
                    if (size != WidgetSize.MINI) assertNoWater(root, "$size attached running")
                    first = animation.displayedChild
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var advanced = false
                while (!advanced && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                    scenario.onActivity { advanced = root.findViewById<ViewFlipper>(if (size == WidgetSize.MINI) R.id.widget_water_active else R.id.widget_drum_active).displayedChild != first }
                }
                assertTrue("The $size host must advance a real task frame without test-driven showNext", advanced)
                scenario.onActivity { activity ->
                    provider.render(activity, 906, task(now).copy(taskEstimatedFinishAtMillis = now), size, now)
                        .reapply(activity, root)
                    if (size == WidgetSize.MINI) assertWaterState(root, false, "$size awaiting device confirmation")
                    else {
                        assertNoWater(root, "$size awaiting device confirmation")
                        assertNull(root.findViewById<ViewFlipper>(R.id.widget_drum_active))
                    }
                    assertEquals(100, root.findViewById<ProgressBar>(R.id.widget_progress).progress)
                    provider.render(activity, 906, task(now).copy(taskStartedAtMillis = null), size, now)
                        .reapply(activity, root)
                    if (size == WidgetSize.MINI) assertWaterState(root, false, "$size unknown percentage")
                    else assertNoWater(root, "$size unknown percentage")
                }
            }
        }
    }

    private fun measure(view: View, widthDp: Int, heightDp: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view.context, widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view.context, heightDp), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value + .5f).toInt()
    private fun rect(root: View, child: View): Rect = Rect(0, 0, child.width, child.height).also {
        if (root !== child) (root as ViewGroup).offsetDescendantRectToMyCoords(child, it)
    }
    private fun assertInside(root: View, child: View) {
        val bounds = rect(root, child)
        assertTrue("${child.resources.getResourceEntryName(child.id)} must have usable width: $bounds", bounds.width() > 0)
        assertTrue("${child.resources.getResourceEntryName(child.id)} must stay inside $bounds / ${root.width}x${root.height}",
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.width && bounds.bottom <= root.height)
    }
    private fun assertReadable(label: TextView, case: String) {
        val layout = requireNotNull(label.layout)
        val name = label.resources.getResourceEntryName(label.id)
        assertTrue("$name must contain a laid out line ($case)", layout.lineCount > 0)
        assertTrue("$name must not clip its text vertically ($case; text=${label.text}, textSize=${label.textSize}, layout=${layout.width}x${layout.height}, view=${label.width}x${label.height})",
            layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals("$name must remain readable ($case)", 0, layout.getEllipsisCount(line))
        }
    }
    private fun assertTextInset(root: View, label: TextView, case: String,
        card: Rect = Rect(0, 0, root.width, root.height)) {
        val layout = requireNotNull(label.layout)
        val box = rect(root, label)
        val availableHeight = label.height - label.compoundPaddingTop - label.compoundPaddingBottom
        val vertical = when (label.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> availableHeight - layout.height
            Gravity.CENTER_VERTICAL -> (availableHeight - layout.height) / 2
            else -> 0
        }.coerceAtLeast(0)
        val left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) } + box.left + label.compoundPaddingLeft
        val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) } + box.left + label.compoundPaddingLeft
        val top = box.top + label.compoundPaddingTop + vertical
        val bottom = top + layout.height
        val safe = dp(root.context, 6)
        assertTrue("Visible text must keep safe space from every rounded card edge ($case, text=${label.text}, glyph=[$left,$top,$right,$bottom], card=$card)",
            left >= card.left + safe && top >= card.top + safe && right <= card.right - safe && bottom <= card.bottom - safe)
    }
    private fun artworkBounds(root: View, image: ImageView): RectF {
        val drawable = requireNotNull(image.drawable)
        val content = RectF(drawable.bounds)
        image.imageMatrix.mapRect(content)
        val box = rect(root, image)
        content.offset((box.left + image.paddingLeft).toFloat(), (box.top + image.paddingTop).toFloat())
        content.intersect(RectF(box))
        return content
    }
    private fun assertNoWater(view: View, case: String) {
        for (id in listOf(R.id.widget_water_holder, R.id.widget_water_active, R.id.widget_water_frame_0,
            R.id.widget_water_frame_1, R.id.widget_water_frame_2)) {
            assertNull("Only the 2×1 mini can install water artwork or reserve its space ($case, id=$id)", view.findViewById<View>(id))
        }
    }
    private fun assertWaterState(view: View, animated: Boolean, case: String) {
        val holder = view.findViewById<ViewGroup>(R.id.widget_water_holder)
        val flipper = view.findViewById<ViewFlipper>(R.id.widget_water_active)
        val static = view.findViewById<ImageView>(R.id.widget_machine_ring)
        assertNotNull("Static water artwork must be ready for a later host reapply ($case)", static.drawable)
        assertEquals(if (animated) View.GONE else View.VISIBLE, static.visibility)
        assertEquals(if (animated) View.VISIBLE else View.GONE, holder.visibility)
        if (animated) {
            assertNotNull("A trustworthy running task must have a water animation ($case)", flipper)
            assertEquals("The water animation must have three native frames ($case)", 3, flipper.childCount)
            for (id in listOf(R.id.widget_water_frame_0, R.id.widget_water_frame_1, R.id.widget_water_frame_2)) {
                assertNotNull("Every water frame must contain artwork ($case)", view.findViewById<ImageView>(id).drawable)
            }
        } else {
            assertNull("Non-running or unknown progress must detach the water timer ($case)", flipper)
            assertEquals("An idle holder must not retain an auto-start child ($case)", 0, holder.childCount)
        }
    }
    private fun assertStatusFitsMinimum(view: View, size: WidgetSize) {
        val (width, height) = when (size) {
            WidgetSize.MINI -> error("Mini cards have a dedicated layout matrix")
            WidgetSize.COMPACT -> 150 to 150
            WidgetSize.WIDE -> 250 to 120
        }
        measure(view, width, height)
        for (id in listOf(R.id.widget_task_section, R.id.widget_task, R.id.widget_task_eta)) assertInside(view, view.findViewById(id))
        val config = view.resources.configuration
        val case = "$size status at $width x $height, ${config.locales[0]}, font ${config.fontScale}, text=${view.findViewById<TextView>(R.id.widget_task).text} / ${view.findViewById<TextView>(R.id.widget_task_eta).text}"
        for (id in listOf(R.id.widget_task, R.id.widget_task_eta)) {
            assertReadable(view.findViewById(id), case)
            assertTextInset(view, view.findViewById(id), case)
        }
        val title = rect(view, view.findViewById(R.id.widget_task))
        val eta = rect(view, view.findViewById(R.id.widget_task_eta))
        assertTrue("Status labels cannot overlap", eta.top >= title.bottom)
        val label = view.findViewById<TextView>(R.id.widget_progress_label)
        if (label.visibility == View.VISIBLE) {
            assertInside(view, label)
            assertReadable(label, "$size status progress")
            assertTrue("Status must remain above progress", eta.bottom <= rect(view, label).top)
        }
    }
    private fun assertNoRefreshControls(view: View) {
        assertNull(view.findViewById<View>(R.id.widget_refresh))
        assertNull(view.findViewById<View>(R.id.widget_updated))
        assertFalse(view.contentDescription.toString().contains("更新于"))
        assertFalse(view.contentDescription.toString().contains("Updated "))
    }
    private fun allText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { allText(view.getChildAt(it)) }
        else -> ""
    }
    private fun containsChronometer(view: View): Boolean = view is Chronometer ||
        view is ViewGroup && (0 until view.childCount).any { containsChronometer(view.getChildAt(it)) }
    private fun isRunning(drawable: Drawable?): Boolean = when (drawable) {
        is Animatable -> drawable.isRunning
        is LayerDrawable -> (0 until drawable.numberOfLayers).any { isRunning(drawable.getDrawable(it)) }
        else -> drawable?.current?.takeIf { it !== drawable }?.let(::isRunning) ?: false
    }
    private fun captureView(view: View, name: String) {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(image)); image.writeToTestStorage(name) } finally { image.recycle() }
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
}
