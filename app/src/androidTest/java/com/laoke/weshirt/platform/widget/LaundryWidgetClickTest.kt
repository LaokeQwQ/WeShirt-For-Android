package com.laoke.weshirt.platform.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.laoke.weshirt.R
import com.laoke.weshirt.data.SecureSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LaundryWidgetClickTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext

    @Test fun installedWidgetsShareAutomaticRefreshOnlyWhileAnActiveTaskExists() {
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 658)
        val worker = WorkManager.getInstance(app)
        val sessions = SecureSession(app)
        val originalSession = sessions.read()
        val prefs = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        val oldEndpoint = prefs.getString("endpoint", null)
        val originalSnapshot = LaundryWidgetStore.read(app)
        val originalError = LaundryWidgetStore.refreshError(app)
        LaundryWidgetRefresh.cancel(app)
        widgetBinding("grantbind")
        try {
            onMain { host.startListening() }
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val origin = "https://127.0.0.1:${server.localPort}"
                prefs.edit().putString("endpoint", origin).commit()
                sessions.save(JSONObject().put("origin", origin).put("accessToken", "ma1.refresh-fixture.fixture")
                    .put("expiresAt", System.currentTimeMillis() + 600_000)
                    .put("user", JSONObject().put("id", "refresh-user")))
                val scope = requireNotNull(LaundryWidgetStore.captureScope(app))
                val now = System.currentTimeMillis()
                val idle = LaundryWidgetSnapshot("测试楼", 2, 0, 0, now - 120_001)
                assertTrue(LaundryWidgetStore.update(app, idle, scope))
                val views = LaundryWidgetRegistry.entries.map { entry ->
                    val id = host.allocateAppWidgetId()
                    val component = ComponentName(app, entry.providerClass)
                    assertTrue(manager.bindAppWidgetIdIfAllowed(id, component))
                    val view = onMain { host.createView(app, id, requireNotNull(manager.getAppWidgetInfo(id))) }
                    await("Widget did not receive the saved snapshot") {
                        onMain { view.findViewById<View>(R.id.widget_root)?.contentDescription?.contains(idle.buildingName) == true }
                    }
                    view
                }
                fun jobs() = worker.getWorkInfosForUniqueWork(LaundryWidgetRefresh.REFRESH_NAME)
                    .get(4, TimeUnit.SECONDS).filterNot { it.state.isFinished }
                assertNull(LaundryWidgetRefresh.reconcile(app))
                assertTrue("Idle widgets must not schedule a network job", jobs().isEmpty())
                val snapshot = idle.copy(updatedAtMillis = now - 1_800_000, taskMachineName = "1号洗衣机",
                    taskEstimatedFinishAtMillis = now + 600_000, taskStartedAtMillis = now - 600_000,
                    taskId = "active-test-task", taskObservedAtMillis = now, taskAcknowledgedAtMillis = now)
                assertTrue(LaundryWidgetStore.update(app, snapshot, scope))
                assertFalse(LaundryWidgetStore.update(app, idle.copy(taskObservedAtMillis = now + 1), scope))
                val saved = requireNotNull(LaundryWidgetStore.read(app))
                assertEquals(idle.updatedAtMillis, saved.updatedAtMillis)
                assertEquals(WidgetTaskState.ESTIMATED, LaundryWidgetState.taskState(saved, System.currentTimeMillis()))
                // Exercise the same framework callback that fires when a launcher adds a widget.
                val entry = LaundryWidgetRegistry.entries.first()
                val installed = manager.getAppWidgetIds(ComponentName(app, entry.providerClass))
                onMain { entry.create().onUpdate(app, manager, installed) }
                server.accept().use { socket ->
                    socket.soTimeout = 4_000
                    assertEquals(22, socket.getInputStream().read())
                    assertNull(LaundryWidgetRefresh.reconcile(app))
                    val job = jobs().single()
                    assertNotNull(job.periodicityInfo)
                    assertEquals(TimeUnit.MINUTES.toMillis(15), job.periodicityInfo?.repeatIntervalMillis)
                }
                await("The periodic job did not persist the network failure") {
                    jobs().singleOrNull()?.state == WorkInfo.State.ENQUEUED &&
                        LaundryWidgetStore.refreshError(app)?.contains("HTTP 0") == true
                }
                views.forEach { view ->
                    await("Widget did not display the refresh failure") {
                        onMain { view.findViewById<TextView>(R.id.widget_task_eta)?.text?.toString() == app.getString(R.string.widget_refresh_failed) }
                    }
                    onMain {
                        assertNull(view.findViewById<View>(R.id.widget_refresh))
                        assertNull(view.findViewById<View>(R.id.widget_updated))
                    }
                }
                assertEquals(idle.updatedAtMillis, LaundryWidgetStore.read(app)?.updatedAtMillis)

                assertTrue(LaundryWidgetStore.update(app, snapshot.copy(taskCompletionConfirmed = true), scope))
                requireNotNull(LaundryWidgetRefresh.reconcile(app)).result.get(4, TimeUnit.SECONDS)
                assertTrue("A confirmed completion must cancel periodic network work", jobs().isEmpty())
            }
        } finally {
            host.stopListening()
            host.deleteHost()
            widgetBinding("revokebind")
            LaundryWidgetRefresh.cancel(app)
            LaundryWidgetStore.clear(app)
            prefs.edit().putString("endpoint", oldEndpoint).commit()
            if (originalSession == null) sessions.clear() else sessions.save(originalSession)
            if (originalSnapshot != null) LaundryWidgetStore.update(app, originalSnapshot)
            if (originalError != null) LaundryWidgetStore.setError(app, originalError, LaundryWidgetStore.captureScope(app))
        }
    }

    private fun widgetBinding(action: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("appwidget $action --package ${app.packageName}")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun await(message: String, ready: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready() && System.nanoTime() < until) Thread.sleep(50)
        assertTrue(message, ready())
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get(4, TimeUnit.SECONDS)
    }
}
