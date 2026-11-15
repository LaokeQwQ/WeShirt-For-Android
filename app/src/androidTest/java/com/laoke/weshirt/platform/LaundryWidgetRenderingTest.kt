package com.laoke.weshirt.platform

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
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

    private fun task(now: Long) = LaundryWidgetSnapshot("Test building", 9, 0, 0, now,
        "1号洗衣机", now + 600_000, taskStartedAtMillis = now - 600_000)

    @Test fun miniCardsKeepShortTaskStatesReadableAndFullDetailsAccessible() {
        data class Case(val name: String, val snapshot: LaundryWidgetSnapshot?, val status: Int, val progress: Int?)
        val provider = LaundryWidgetMiniProvider()
        val now = System.currentTimeMillis()
        val cases = listOf(
            Case("guest", null, R.string.widget_mini_sign_in_detail, null),
            Case("idle", LaundryWidgetSnapshot("Test building", 9, 0, 0, now), R.string.widget_mini_no_task, null),
            Case("running", task(now), R.string.widget_mini_remaining, 50),
            Case("awaiting", task(now).copy(taskEstimatedFinishAtMillis = now), R.string.widget_mini_awaiting, 100),
            Case("stale", task(now).copy(updatedAtMillis = now - 600_001), R.string.widget_mini_unverified, null),
            Case("ready", task(now).copy(taskCompletionConfirmed = true), R.string.widget_mini_ready, 100),
            Case("missing-start", task(now).copy(taskStartedAtMillis = null), R.string.widget_mini_remaining, null),
            Case("missing-finish", task(now).copy(taskEstimatedFinishAtMillis = null), R.string.widget_mini_unverified, null),
        )
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
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
                            for ((width, height) in listOf(110 to 40, 180 to 80)) for (case in cases) {
                                val view = provider.render(context, 905, case.snapshot, WidgetSize.MINI, now)
                                    .apply(context, FrameLayout(context))
                                measure(view, width, height)
                                val label = "2x1 at ${width}x$height, ${locale.language}, night $night, font $font, ${case.name}"
                                captureView(view,
                                    "native-widget-2x1-${width}x$height-${locale.language}-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${if (font == 1.4f) "large-font" else "default-font"}-${case.name}")
                                for (id in listOf(R.id.widget_machine, R.id.widget_task_section, R.id.widget_task, R.id.widget_task_eta, R.id.widget_refresh)) {
                                    val child = view.findViewById<View>(id)
                                    assertInside(view, child)
                                    assertTrue("Mini content must retain height ($label, id=$id)", child.height > 0)
                                }
                                val title = view.findViewById<TextView>(R.id.widget_task)
                                val eta = view.findViewById<TextView>(R.id.widget_task_eta)
                                val expectedTitle = context.getString(when {
                                    case.snapshot == null -> R.string.widget_mini_sign_in
                                    case.snapshot.taskMachineName == null -> R.string.widget_idle
                                    else -> R.string.widget_mini_machine
                                }, "1")
                                assertEquals("The mini title must identify the task ($label)", expectedTitle, title.text.toString())
                                val expectedStatus = if (case.status == R.string.widget_mini_remaining)
                                    context.getString(case.status, 10) else context.getString(case.status)
                                assertEquals("The mini status must preserve task semantics ($label)", expectedStatus, eta.text.toString())
                                assertReadable(title, label)
                                assertReadable(eta, label)
                                val machine = rect(view, view.findViewById(R.id.widget_machine))
                                val section = rect(view, view.findViewById(R.id.widget_task_section))
                                val refresh = view.findViewById<View>(R.id.widget_refresh)
                                assertTrue("Mini dial and task cannot overlap ($label)", machine.right <= section.left)
                                assertTrue("Mini task and refresh cannot overlap ($label)", section.right <= rect(view, refresh).left)
                                assertTrue("Mini task labels cannot overlap ($label)", rect(view, title).bottom <= rect(view, eta).top)
                                assertTrue("Mini refresh must retain its click target ($label)", refresh.width >= dp(context, 28) && refresh.height >= dp(context, 28))
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_header).visibility)
                                val description = view.contentDescription.toString()
                                val building = case.snapshot?.buildingName ?: context.getString(R.string.widget_brand)
                                val updated = case.snapshot?.let {
                                    context.getString(R.string.widget_last_sync, SimpleDateFormat("HH:mm", locale).format(Date(it.updatedAtMillis)))
                                } ?: context.getString(R.string.widget_update_unknown)
                                assertTrue("Hidden mini building must remain accessible ($label)", description.contains(building))
                                assertTrue("Hidden mini update time must remain accessible ($label)", description.contains(updated))
                                case.snapshot?.taskMachineName?.let { fullName ->
                                    assertTrue("The full machine name remains accessible ($label)", description.contains(fullName))
                                }
                                assertNull("The mini dial must not install a washer animation ($label)", view.findViewById<ViewFlipper>(R.id.widget_drum_active))
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_drum_holder).visibility)
                                val dial = view.findViewById<ImageView>(R.id.widget_machine_ring)
                                assertEquals(View.VISIBLE, dial.visibility)
                                assertNotNull("Mini cards must render their progress dial ($label)", dial.drawable)
                                assertInside(view, dial)
                                val minimumDial = dp(context, if (width == 110) 24 else 44)
                                assertTrue("Mini progress must be recognizable at each host size ($label)",
                                    minOf(dial.width, dial.height) >= minimumDial)
                                val progress = view.findViewById<ProgressBar>(R.id.widget_progress)
                                assertEquals("The mini dial must not duplicate a determinate progress bar ($label)", View.GONE, progress.visibility)
                                if (case.progress != null) assertEquals(case.progress, progress.progress)
                                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_progress_track).visibility)
                                val activity = view.findViewById<View>(R.id.widget_activity)
                                assertEquals("Mini activity is reserved for a trustworthy running task ($label)",
                                    if (case.name in listOf("running", "missing-start")) View.VISIBLE else View.GONE, activity.visibility)
                                if (activity.visibility == View.VISIBLE) assertInside(view, activity)
                                assertFalse("Mini cards must not use a host countdown ($label)", containsChronometer(view))
                                assertFalse("Mini cards cannot show a zero or negative estimate ($label)",
                                    eta.text.toString() == context.getString(R.string.widget_mini_remaining, 0) || eta.text.toString().contains("-1"))
                            }
                        }
                    }
                }
                val request = provider.refreshIntent(app, 905)
                assertEquals(ComponentName(app, LaundryWidgetMiniProvider::class.java), request.component)
                assertEquals(905, provider.acceptedRefreshId(app, request, intArrayOf(905)))
                assertNull(provider.acceptedRefreshId(app, request, intArrayOf(906)))
                assertNull(LaundryWidgetProvider().acceptedRefreshId(app, request, intArrayOf(905)))
                val pending = provider.refreshPendingIntent(app, 905)
                assertTrue(pending.isBroadcast)
                if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(pending.isImmutable)
                val view = provider.render(app, 905, task(now), WidgetSize.MINI, now).apply(app, FrameLayout(app))
                assertTrue("Mini refresh remains independent from task navigation", view.findViewById<View>(R.id.widget_refresh).performClick())
            }
            instrumentation.waitForIdleSync()
            assertEquals("Mini refresh must not launch the app", 0, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
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
                                WidgetSize.COMPACT -> listOf(110 to 110, 180 to 180)
                                WidgetSize.WIDE -> listOf(250 to 110, 320 to 140)
                                WidgetSize.EXPANDED -> listOf(250 to 250, 320 to 320)
                                WidgetSize.LARGE -> listOf(250 to 390, 320 to 480)
                            }
                            for ((width, height) in dimensions) {
                                val view = LaundryWidgetProvider().render(context, 900, task(now), size, now)
                                    .apply(context, FrameLayout(context))
                                measure(view, width, height)
                                // Save before assertions so a failing size is still inspectable.
                                captureView(view,
                                    "native-widget-${size.columns}x${size.rows}-${width}x$height-${locale.language}-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${if (font == 1.4f) "large-font" else "default-font"}")
                                assertEquals("Test building", view.findViewById<TextView>(R.id.widget_building).text.toString())
                                assertEquals("1号洗衣机", view.findViewById<TextView>(R.id.widget_task).text.toString())
                                assertEquals(context.getString(R.string.widget_remaining, 10), view.findViewById<TextView>(R.id.widget_task_eta).text.toString())
                                assertEquals(50, view.findViewById<ProgressBar>(R.id.widget_progress).progress)
                                assertEquals(context.getString(R.string.widget_progress_percent, 50), view.findViewById<TextView>(R.id.widget_progress_label).text.toString())
                                assertEquals(context.getString(R.string.widget_last_sync, SimpleDateFormat("HH:mm", locale).format(Date(now))), view.findViewById<TextView>(R.id.widget_updated).text.toString())
                                assertFalse("A host countdown must never turn negative", containsChronometer(view))
                                assertFalse("The widget describes the user's task, not an idle-machine count", allText(view).contains("台空闲"))
                                assertFalse("The widget does not show a second usage summary", allText(view).contains("使用中") || allText(view).contains("Unknown") || allText(view).contains("未知"))
                                val refresh = view.findViewById<View>(R.id.widget_refresh)
                                val minimumTarget = when (size) {
                                    WidgetSize.MINI -> 28
                                    WidgetSize.COMPACT, WidgetSize.WIDE -> 32
                                    WidgetSize.EXPANDED -> 36
                                    WidgetSize.LARGE -> 40
                                }
                                assertTrue(refresh.width >= dp(context, minimumTarget))
                                assertTrue(refresh.height >= dp(context, minimumTarget))
                                for (id in listOf(R.id.widget_building, R.id.widget_updated, R.id.widget_refresh, R.id.widget_task_section, R.id.widget_machine, R.id.widget_task, R.id.widget_task_eta, R.id.widget_progress, R.id.widget_progress_label)) {
                                    assertInside(view, view.findViewById(id))
                                }
                                val building = rect(view, view.findViewById(R.id.widget_building))
                                val updated = rect(view, view.findViewById(R.id.widget_updated))
                                if (size == WidgetSize.WIDE) {
                                    assertTrue("The wide card's building and update time cannot overlap", building.bottom <= updated.top)
                                    assertTrue("The building must stay beside refresh", building.right <= rect(view, refresh).left)
                                } else if (size != WidgetSize.COMPACT) {
                                    assertTrue("Title and update time cannot overlap", building.right <= updated.left)
                                }
                                assertTrue("Update time must stay beside refresh", updated.right <= rect(view, refresh).left)
                                for (id in listOf(R.id.widget_updated, R.id.widget_task, R.id.widget_task_eta, R.id.widget_progress_label)) {
                                    assertReadable(view.findViewById(id), "${size.columns}x${size.rows} at ${width}x$height, ${locale.language}, font $font")
                                }
                                val header = rect(view, view.findViewById(R.id.widget_header))
                                val section = rect(view, view.findViewById(R.id.widget_task_section))
                                val machine = rect(view, view.findViewById(R.id.widget_machine))
                                val title = rect(view, view.findViewById(R.id.widget_task))
                                val eta = rect(view, view.findViewById(R.id.widget_task_eta))
                                val progress = rect(view, view.findViewById(R.id.widget_progress_label))
                                val measuredCase = "${size.columns}x${size.rows} at ${width}x$height, ${locale.language}, night $night, font $font; machine=$machine, section=$section, header=$header, title=$title, eta=$eta, progress=$progress"
                                assertTrue("The task area cannot collapse ($measuredCase)", section.height() > 0)
                                assertTrue("The washer illustration must retain height ($measuredCase)", machine.height() >= dp(context, if (size == WidgetSize.COMPACT && width == 110) 4 else 12))
                                if (size == WidgetSize.WIDE) {
                                    assertTrue("The wide washer must stay to the left of the header and task ($measuredCase)",
                                        machine.right <= minOf(header.left, section.left, title.left, eta.left, progress.left))
                                    assertTrue("The wide washer must remain prominent ($measuredCase)",
                                        machine.width() >= dp(context, 72) && machine.height() >= dp(context, if (height == 110) 80 else 100))
                                    assertTrue("The wide task text must stay below its own header ($measuredCase)", title.top >= header.bottom)
                                } else {
                                    assertTrue("Task content must stay below the header ($measuredCase)", minOf(machine.top, title.top) >= header.bottom)
                                }
                                assertTrue("The machine name and remaining time cannot overlap ($measuredCase)", title.bottom <= eta.top)
                                assertTrue("The remaining time must stay above progress ($measuredCase)", eta.bottom <= progress.top)
                                if (size != WidgetSize.WIDE) {
                                    assertTrue("The centered illustration must stay above its labels ($measuredCase)", machine.bottom <= title.top)
                                }
                                if (size == WidgetSize.EXPANDED || size == WidgetSize.LARGE) {
                                    val note = view.findViewById<TextView>(R.id.widget_task_note)
                                    assertInside(view, note)
                                    assertTrue("Status notes must stay below progress", rect(view, note).top >= progress.bottom)
                                }
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
            val snapshots = listOf(null, LaundryWidgetSnapshot("Test building", 9, 0, 0, now),
                task(now).copy(taskEstimatedFinishAtMillis = now),
                task(now).copy(taskCompletionConfirmed = true),
                task(now).copy(taskStartedAtMillis = null),
                task(now).copy(updatedAtMillis = now - 600_001))
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                val config = Configuration(app.resources.configuration).apply { setLocale(locale); fontScale = 1.4f }
                val context = app.createConfigurationContext(config)
                for (size in fullCardSizes) for (snapshot in snapshots) {
                    val view = LaundryWidgetProvider().render(context, 904, snapshot, size, now).apply(context, FrameLayout(context))
                    assertStatusFitsMinimum(view, size)
                }
            }
        }
    }

    @Test fun idleAwaitingConfirmedAndStaleTasksHaveNoFalseCountdownOrAnimation() {
        instrumentation.runOnMainSync {
            val now = System.currentTimeMillis()
            val provider = LaundryWidgetProvider()
            for (size in fullCardSizes) {
                val idle = provider.render(app, 901, LaundryWidgetSnapshot("Test building", 9, 0, 0, now), size, now).apply(app, FrameLayout(app))
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
                assertEquals(app.getString(R.string.widget_progress_unknown), missingStart.findViewById<TextView>(R.id.widget_progress_label).text.toString())
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
            assertEquals(app.getString(R.string.widget_tap_refresh), signedIn.findViewById<TextView>(R.id.widget_task_eta).text.toString())
        }
        assertTrue(LaundryWidgetStore.setError(app, "HTTP 503 · event_widget_fixture", scope))
        instrumentation.runOnMainSync {
            val failed = provider.render(app, 903, null, WidgetSize.EXPANDED, System.currentTimeMillis()).apply(app, FrameLayout(app))
            assertEquals(app.getString(R.string.widget_refresh_failed), failed.findViewById<TextView>(R.id.widget_task_eta).text.toString())
            assertTrue(failed.findViewById<TextView>(R.id.widget_task_note).text.contains("event_widget_fixture"))
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

    @Test fun attachedHostActuallyFlipsOnlyRunningDrumAndRefreshIsAnIndependentImmutableBroadcast() {
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
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    scenario.onActivity { activity ->
                        val refresh = provider.refreshPendingIntent(activity, 902)
                        assertTrue(refresh.isBroadcast)
                        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(refresh.isImmutable)
                        assertEquals(activity.packageName, refresh.creatorPackage)
                        assertTrue(root.findViewById<View>(R.id.widget_refresh).performClick())
                        val request = Intent(activity, LaundryWidgetProvider::class.java).setAction(LaundryWidgetProvider.ACTION_REFRESH)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 902)
                        assertEquals(902, provider.acceptedRefreshId(activity, request, intArrayOf(902)))
                        assertNull(provider.acceptedRefreshId(activity, request, intArrayOf(903)))
                        assertNull(provider.acceptedRefreshId(activity, Intent(request).setComponent(null), intArrayOf(902)))
                        assertNull(provider.acceptedRefreshId(activity, Intent(request).setAction(Intent.ACTION_VIEW), intArrayOf(902)))
                        captureView(root, "native-widget-attached-running")
                    }
                    instrumentation.waitForIdleSync()
                    assertEquals("The refresh button must not launch the app", 0, monitor.hits)
                } finally { instrumentation.removeMonitor(monitor) }
                scenario.onActivity { activity ->
                    provider.render(activity, 902, LaundryWidgetSnapshot("Test building", 9, 0, 0, now), WidgetSize.WIDE, now).reapply(activity, root)
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
    private fun assertStatusFitsMinimum(view: View, size: WidgetSize) {
        val (width, height) = when (size) {
            WidgetSize.MINI -> error("Mini cards have a dedicated layout matrix")
            WidgetSize.COMPACT -> 110 to 110
            WidgetSize.WIDE -> 250 to 110
            WidgetSize.EXPANDED -> 250 to 250
            WidgetSize.LARGE -> 250 to 390
        }
        measure(view, width, height)
        for (id in listOf(R.id.widget_task_section, R.id.widget_task, R.id.widget_task_eta)) assertInside(view, view.findViewById(id))
        val config = view.resources.configuration
        val case = "$size status at $width x $height, ${config.locales[0]}, font ${config.fontScale}, text=${view.findViewById<TextView>(R.id.widget_task).text} / ${view.findViewById<TextView>(R.id.widget_task_eta).text}"
        for (id in listOf(R.id.widget_updated, R.id.widget_task, R.id.widget_task_eta)) assertReadable(view.findViewById(id), case)
        val header = rect(view, view.findViewById(R.id.widget_header))
        val title = rect(view, view.findViewById(R.id.widget_task))
        val eta = rect(view, view.findViewById(R.id.widget_task_eta))
        assertTrue("Status must remain below the header", title.top >= header.bottom)
        assertTrue("Status labels cannot overlap", eta.top >= title.bottom)
        val label = view.findViewById<TextView>(R.id.widget_progress_label)
        if (label.visibility == View.VISIBLE) {
            assertInside(view, label)
            assertReadable(label, "$size status progress")
            assertTrue("Status must remain above progress", eta.bottom <= rect(view, label).top)
        }
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
