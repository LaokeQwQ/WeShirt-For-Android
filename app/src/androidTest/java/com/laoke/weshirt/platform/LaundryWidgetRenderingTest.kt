package com.laoke.weshirt.platform

import android.appwidget.AppWidgetManager
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

    @Before fun prepare() {
        val prefs = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        oldEndpoint = prefs.getString("endpoint", null)
        prefs.edit().putString("endpoint", "https://widget.invalid").commit()
        SecureSession(app).clear()
        LaundryWidgetStore.clear(app)
    }

    @After fun cleanup() {
        SecureSession(app).clear()
        LaundryWidgetStore.clear(app)
        val edit = app.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
        if (oldEndpoint == null) edit.remove("endpoint") else edit.putString("endpoint", oldEndpoint)
        edit.commit()
    }

    private fun task(now: Long) = LaundryWidgetSnapshot("Test building", 9, 0, 0, now,
        "1号洗衣机", now + 600_000, taskStartedAtMillis = now - 600_000)

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
                        for (size in WidgetSize.entries) {
                            for (width in if (size == WidgetSize.COMPACT) listOf(180, 260, 320) else listOf(260, 320)) {
                                val height = when {
                                    width == 180 -> 140
                                    size == WidgetSize.EXPANDED -> 340
                                    else -> 180
                                }
                                val view = LaundryWidgetProvider().render(context, 900, task(now), size, now)
                                    .apply(context, FrameLayout(context))
                                measure(view, width, height)
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
                                assertTrue(refresh.width >= dp(context, 40))
                                assertTrue(refresh.height >= dp(context, 40))
                                for (id in listOf(R.id.widget_building, R.id.widget_updated, R.id.widget_refresh, R.id.widget_machine, R.id.widget_task, R.id.widget_task_eta, R.id.widget_progress_label)) {
                                    assertInside(view, view.findViewById(id))
                                }
                                val building = rect(view, view.findViewById(R.id.widget_building))
                                val updated = rect(view, view.findViewById(R.id.widget_updated))
                                if (size != WidgetSize.COMPACT) assertTrue("Title and update time cannot overlap", building.right <= updated.left)
                                if (width == 180) {
                                    for (id in listOf(R.id.widget_updated, R.id.widget_task, R.id.widget_task_eta)) {
                                        val label = view.findViewById<TextView>(id)
                                        assertTrue("The minimum-size label must contain a laid out line", label.layout.lineCount > 0)
                                        assertEquals("The minimum-size label must remain readable", 0, label.layout.getEllipsisCount(label.layout.lineCount - 1))
                                    }
                                    val header = rect(view, view.findViewById(R.id.widget_header))
                                    val task = rect(view, view.findViewById(R.id.widget_task))
                                    val eta = rect(view, view.findViewById(R.id.widget_task_eta))
                                    val progress = rect(view, view.findViewById(R.id.widget_progress_label))
                                    assertTrue("The minimum-size task must stay below the header", task.top >= header.bottom)
                                    assertTrue("The minimum-size time must stay above the progress row", eta.bottom <= progress.top)
                                    if (font == 1.4f) captureView(view,
                                        "native-widget-minimum-180x140-${locale.language}-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-large-font")
                                }
                                if (width == 260 && font == 1.4f && locale == Locale.SIMPLIFIED_CHINESE)
                                    captureView(view, "native-widget-${size.name.lowercase()}-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-large-font")
                            }
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
            for (size in WidgetSize.entries) {
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
                    host.addView(root, FrameLayout.LayoutParams(dp(activity, 320), dp(activity, 180)))
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
