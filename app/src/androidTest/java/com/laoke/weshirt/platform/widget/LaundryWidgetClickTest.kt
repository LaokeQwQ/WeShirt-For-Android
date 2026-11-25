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
import androidx.work.WorkManager
import com.laoke.weshirt.R
import com.laoke.weshirt.data.SecureSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun eachInstalledRefreshIconStartsNetworkWorkAndFailureKeepsLastUpdateTime() {
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
                val snapshot = LaundryWidgetSnapshot("测试楼", 2, 0, 0, now - 120_000)
                assertTrue(LaundryWidgetStore.update(app, snapshot, scope))

                LaundryWidgetRegistry.entries.forEachIndexed { index, entry ->
                    val id = host.allocateAppWidgetId()
                    val component = ComponentName(app, entry.providerClass)
                    assertTrue(manager.bindAppWidgetIdIfAllowed(id, component))
                    val view = onMain { host.createView(app, id, requireNotNull(manager.getAppWidgetInfo(id))) }
                    fun updateTime() = onMain { view.findViewById<TextView>(R.id.widget_updated)?.text?.toString() }
                    await("Widget did not receive the saved snapshot") {
                        onMain { view.findViewById<View>(R.id.widget_root)?.contentDescription?.contains(snapshot.buildingName) == true }
                    }
                    val previousTime = requireNotNull(updateTime())
                    val previousError = LaundryWidgetStore.refreshError(app)
                    val previousWork = worker.getWorkInfosForUniqueWork(LaundryWidgetRefresh.REFRESH_NAME).get(4, TimeUnit.SECONDS)
                        .map { it.id }.toSet()
                    onMain { assertTrue(view.findViewById<View>(R.id.widget_refresh).performClick()) }
                    // The real worker must open a socket. Closing it without a TLS
                    // response exercises the failure path without any external API.
                    server.accept().use { socket ->
                        socket.soTimeout = 4_000
                        assertEquals(22, socket.getInputStream().read()) // TLS handshake record
                        if (index == 0) {
                            val duplicate = requireNotNull(LaundryWidgetRefresh.enqueue(app, id))
                            duplicate.getResult().get(4, TimeUnit.SECONDS)
                            val newJobs = worker.getWorkInfosForUniqueWork(LaundryWidgetRefresh.REFRESH_NAME).get(4, TimeUnit.SECONDS)
                                .filter { it.id !in previousWork }
                            assertEquals("Concurrent taps must share one job", 1, newJobs.size)
                        }
                    }
                    await("Click did not finish the network refresh for ${entry.providerClass.simpleName}") {
                        val work = worker.getWorkInfosForUniqueWork(LaundryWidgetRefresh.REFRESH_NAME).get(4, TimeUnit.SECONDS)
                            .filter { it.id !in previousWork }
                        val error = LaundryWidgetStore.refreshError(app)
                        work.singleOrNull()?.state?.isFinished == true && error?.contains("HTTP 0") == true && error != previousError
                    }
                    await("Widget did not display the refresh failure") {
                        onMain { view.findViewById<TextView>(R.id.widget_task_eta)?.text?.toString() == app.getString(R.string.widget_refresh_failed) }
                    }
                    assertNotEquals(previousError, LaundryWidgetStore.refreshError(app))
                    assertEquals(snapshot.updatedAtMillis, LaundryWidgetStore.read(app)?.updatedAtMillis)
                    assertEquals(previousTime, updateTime())
                    // The next size is clicked immediately, inside the old 30s cooldown.
                }
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
