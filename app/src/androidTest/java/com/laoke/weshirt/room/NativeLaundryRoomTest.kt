package com.laoke.weshirt.room

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs the real Filament engine and packaged materials on a GLES 3 emulator/device. */
@RunWith(AndroidJUnit4::class)
class NativeLaundryRoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun isolateFromAnyConfiguredBackend() {
        instrumentation.targetContext.getSharedPreferences("preferences", 0).edit().clear().putString("endpoint", "").commit()
    }

    @Test fun realSurfaceDrawsMaterialsAndStopsSubmittingInBackground() = withRoom { scenario, room, errors ->
        await("Filament did not submit its first scene frame") { snapshot(room).renderedFrames > 0 }
        var initial = snapshot(room)
        assertNull("Native renderer reported an error", initial.failureEvent)
        assertTrue("The real Filament engine must exist", initial.engineReady)
        assertTrue("A real Surface swap chain must exist", initial.surfaceReady)
        assertEquals("Both lit and unlit materials must compile", 2, initial.materialCount)
        assertEquals(9, initial.machineCount)
        assertTrue("Room meshes must be uploaded", initial.meshCount >= 10)
        assertTrue(errors.get().isEmpty())
        assertSurfaceContainsRenderedGeometry(room)

        // Explicit lifecycle pause is the same API used by the Compose lifecycle observer.
        onMain { room.lifecyclePause() }
        val paused = snapshot(room).renderedFrames
        onMain { room.setMachines(originalMachines(RoomMachineState.FINISHING)) }
        SystemClock.sleep(350)
        assertEquals("Invalidating a paused room must render zero frames", paused, snapshot(room).renderedFrames)
        assertEquals(0, onMain { room.qualityState.targetFps })

        scenario.moveToState(Lifecycle.State.CREATED)
        val background = snapshot(room).renderedFrames
        SystemClock.sleep(350)
        assertEquals("Stopped Activity must submit zero frames", background, snapshot(room).renderedFrames)

        scenario.moveToState(Lifecycle.State.RESUMED)
        await("Resuming did not restart actual frame submissions") { snapshot(room).renderedFrames > background }
        initial = snapshot(room)
        assertNull(initial.failureEvent)
        assertTrue(errors.get().isEmpty())

        onMain { room.dispose(); room.dispose() }
        val disposed = snapshot(room)
        assertTrue(disposed.disposed)
        assertFalse(disposed.engineReady)
        assertEquals(0, disposed.meshCount)
        onMain { room.lifecycleResume(); room.setMachines(originalMachines()) }
        SystemClock.sleep(150)
        assertEquals("A disposed room cannot restart rendering", disposed.renderedFrames, snapshot(room).renderedFrames)
    }

    @Test fun tappingEveryOriginalDoorReturnsItsBusinessMachineId() = withRoom { _, room, errors ->
        await("Scene did not become ready for picking") { snapshot(room).renderedFrames > 0 }
        val selected = AtomicReference<String?>(null)
        onMain { room.onMachineSelected = selected::set }
        // Verify the actual initial overview before any selection changes the camera.
        originalMachines().forEach { machine ->
            assertNotNull("Initial framing must show the door for ${machine.slot}", onMain { room.machineScreenPosition(machine.id) })
        }
        originalMachines().forEach { machine ->
            // Selection deliberately flies to a close-up. Return to the overview
            // and wait for its submitted frame before finding the next real door.
            val beforeReset = onMain { room.resetCamera(); room.renderStatistics.renderedFrames }
            await("Overview did not render after selecting the previous door") { snapshot(room).renderedFrames > beforeReset }
            selected.set(null)
            val center = onMain { room.machineScreenPosition(machine.id) }
            assertNotNull("Initial framing must show the door for ${machine.slot}", center)
            onMain { tap(room, requireNotNull(center).x, center.y) }
            await("Door ${machine.slot} did not dispatch a business id") { selected.get() != null }
            assertEquals(machine.id, selected.get())
        }
        assertTrue(errors.get().isEmpty())
    }

    @Test fun repeatedUpstreamSlotsRemainDistinctNativeMachines() = withRoom { _, room, errors ->
        val machines = (0 until 14).map { index ->
            RoomMachine("floor-machine-$index", if(index % 2 == 0) "1" else "unmapped", "Machine $index", RoomMachineState.IDLE)
        }
        val before = snapshot(room).renderedFrames
        onMain { room.setMachines(machines) }
        await("Generic room did not submit geometry") {
            snapshot(room).let { it.machineCount == 14 && it.renderedFrames > before }
        }
        assertNull(snapshot(room).failureEvent)
        machines.forEach { machine ->
            assertNotNull("Generic layout dropped ${machine.id}", onMain { room.machineScreenPosition(machine.id) })
        }
        assertTrue(errors.get().isEmpty())
    }

    @Test fun pendingTapCannotOpenADifferentMachineAfterPauseOrLayoutChange() = withRoom { _, room, _ ->
        await("Scene did not become ready") { snapshot(room).renderedFrames > 0 }
        val selected = AtomicReference<String?>(null)
        onMain {
            room.onMachineSelected = selected::set
            val center = requireNotNull(room.machineScreenPosition("native-machine-4"))
            tap(room, center.x, center.y)
            room.lifecyclePause()
        }
        SystemClock.sleep(400)
        assertNull("A paused scene must cancel its delayed single-tap action", selected.get())
        val paused = snapshot(room).renderedFrames
        onMain { room.lifecycleResume() }
        await("Scene did not resume") { snapshot(room).renderedFrames > paused }
        onMain {
            val center = requireNotNull(room.machineScreenPosition("native-machine-4"))
            tap(room, center.x, center.y)
            room.setMachines(originalMachines().map { it.copy(id = "replacement-${it.id}") })
        }
        SystemClock.sleep(400)
        assertNull("A tap on the previous layout must not select its replacement", selected.get())
    }

    @Test fun aRecreatedNativeSurfaceKeepsTheEngineAndRendersAgain() = withRoom { _, room, errors ->
        await("Scene did not become ready") { snapshot(room).renderedFrames > 0 }
        val surface = onMain { room.getChildAt(0) as SurfaceView }
        onMain { surface.visibility = android.view.View.GONE }
        await("Hidden surface did not release its swap chain") { !snapshot(room).surfaceReady }
        val hidden = snapshot(room).renderedFrames
        SystemClock.sleep(150)
        assertEquals(hidden, snapshot(room).renderedFrames)
        assertTrue("Surface destruction must preserve the reusable engine", snapshot(room).engineReady)
        onMain { surface.visibility = android.view.View.VISIBLE }
        await("Recreated Surface did not submit a frame") { snapshot(room).let { it.surfaceReady && it.renderedFrames > hidden } }
        assertNull(snapshot(room).failureEvent)
        assertTrue(errors.get().isEmpty())
        assertSurfaceContainsRenderedGeometry(room)
    }

    @Test fun realSurfaceShowsWasherAndDryerCloseupsWithOpenAndClosedDoors() = withRoom { _, room, errors ->
        await("Scene did not become ready") { snapshot(room).renderedFrames > 0 }
        for ((slot, state, filename) in listOf(
            Triple("1", RoomMachineState.RUNNING, "native-washer-closed"),
            Triple("1", RoomMachineState.IDLE, "native-washer-open"),
            Triple("D1", RoomMachineState.RUNNING, "native-dryer-closed"),
        )) {
            val id = "native-machine-$slot"
            val before = snapshot(room).renderedFrames
            onMain {
                room.setMachines(originalMachines().map { if (it.id == id) it.copy(state = state) else it })
                room.focusMachine(id)
            }
            await("Close-up did not submit a fresh frame") { snapshot(room).renderedFrames > before }
            // With system animation scale enabled the native camera uses a
            // bounded easing flight; allow it and the door to settle before capture.
            var previous: android.graphics.PointF? = null
            var steadySamples = 0
            await("Close-up camera did not settle") {
                val point = onMain { room.machineScreenPosition(id) }
                val old = previous
                steadySamples = if (point != null && old != null && kotlin.math.abs(point.x-old.x) < .15f && kotlin.math.abs(point.y-old.y) < .15f) steadySamples + 1 else 0
                previous = point
                steadySamples >= 6
            }
            assertSurfaceContainsRenderedGeometry(room, filename)
            assertNull(snapshot(room).failureEvent)
        }
        assertEquals(9, snapshot(room).machineCount)
        assertTrue(errors.get().isEmpty())
    }

    private fun withRoom(test: (ActivityScenario<MainActivity>, LaundryRoomView, AtomicReference<List<String>>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val errors = AtomicReference<List<String>>(emptyList())
            lateinit var room: LaundryRoomView
            lateinit var observer: LifecycleEventObserver
            scenario.onActivity { activity ->
                room = LaundryRoomView(activity).apply {
                    onRendererError = { errors.set(errors.get() + it) }
                    setPerformanceMode(PerformanceMode.AUTO)
                    setMachines(originalMachines())
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(room, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                })
                observer = LifecycleEventObserver { _, event ->
                    when(event) {
                        Lifecycle.Event.ON_RESUME -> room.lifecycleResume()
                        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> room.lifecyclePause()
                        Lifecycle.Event.ON_DESTROY -> room.dispose()
                        else -> Unit
                    }
                }
                activity.lifecycle.addObserver(observer)
                room.lifecycleResume()
            }
            try { test(scenario, room, errors) }
            // Cleanup must work even when an assertion leaves the Activity stopped. Resuming
            // merely to clean up would hide zero-frame failures and can mask the first error.
            finally { onMain { room.dispose() } }
        }
    }

    private fun assertSurfaceContainsRenderedGeometry(room: LaundryRoomView, filename: String = "native-room-3d") {
        val surface = onMain { room.getChildAt(0) as SurfaceView }
        val dimensions = onMain { surface.width to surface.height }
        assertTrue(dimensions.first > 0 && dimensions.second > 0)
        val pixels = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
        try {
            var copyResult = PixelCopy.ERROR_SOURCE_NO_DATA
            var colorCount = 0
            val deadline = SystemClock.uptimeMillis() + 20_000
            while(SystemClock.uptimeMillis() < deadline) {
                val copied = CountDownLatch(1)
                val result = AtomicReference<Int>()
                onMain {
                    PixelCopy.request(surface, pixels, { status -> result.set(status); copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue("Timed out copying the rendered native surface: ${snapshot(room)}", copied.await(5, TimeUnit.SECONDS))
                copyResult = result.get()
                if(copyResult == PixelCopy.SUCCESS) {
                    val colors = mutableSetOf<Int>()
                    for(x in 1..15) for(y in 1..15) colors += pixels.getPixel(x * pixels.width / 16, y * pixels.height / 16)
                    colorCount = colors.size
                    if(colorCount > 8) break
                }
                assertNull("Renderer failed while awaiting actual presentation", snapshot(room).failureEvent)
                SystemClock.sleep(200)
            }
            if(copyResult == PixelCopy.SUCCESS) pixels.writeToTestStorage(filename)
            android.util.Log.i("WeShirtRoomTest", "Baseline native surface: copy=$copyResult, colors=$colorCount, ${snapshot(room)}")
            assertEquals("PixelCopy must read an actually presented GPU surface: ${snapshot(room)}", PixelCopy.SUCCESS, copyResult)
            assertTrue("A clear-only or blank surface cannot count as a rendered room: colors=$colorCount, ${snapshot(room)}", colorCount > 8)
        } finally { pixels.recycle() }
    }

    private fun tap(room: LaundryRoomView, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_UP, x, y, 0)
        try { room.dispatchTouchEvent(down); room.dispatchTouchEvent(up) }
        finally { down.recycle(); up.recycle() }
    }

    private fun snapshot(room: LaundryRoomView) = onMain { room.renderStatistics }

    private fun await(message: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while(SystemClock.uptimeMillis() < deadline) {
            if(condition()) return
            SystemClock.sleep(40)
        }
        assertTrue(message, condition())
    }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }

    private fun originalMachines(state: RoomMachineState = RoomMachineState.RUNNING) =
        listOf("D1", "1", "2", "3", "4", "5", "6", "7", "D2").map { slot ->
            RoomMachine("native-machine-$slot", slot, "Machine $slot", state,
                finishAt = System.currentTimeMillis() + 600_000, kind = if(slot.startsWith("D")) "dryer" else "washer")
        }
}
