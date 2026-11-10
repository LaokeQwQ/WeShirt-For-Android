package com.laoke.weshirt.platform.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.laoke.weshirt.R
import com.laoke.weshirt.platform.push.PushRuntime
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Deliberately excludes account, chat, phone, remarks, avatar, and provider tokens. */
data class LaundryWidgetSnapshot(
    val buildingName: String,
    val idle: Int,
    val busy: Int,
    val unknown: Int,
    val updatedAtMillis: Long,
    val taskMachineName: String? = null,
    val taskEstimatedFinishAtMillis: Long? = null,
)

object LaundryWidgetStore {
    private const val PREFS = "laundry_widget_snapshot"
    @Synchronized fun update(context: Context, snapshot: LaundryWidgetSnapshot) {
        if (!PushRuntime.isSessionActive(context)) return
        val value = JSONObject().put("building", snapshot.buildingName.take(100))
            .put("idle", snapshot.idle.coerceAtLeast(0)).put("busy", snapshot.busy.coerceAtLeast(0))
            .put("unknown", snapshot.unknown.coerceAtLeast(0)).put("updated", snapshot.updatedAtMillis)
            .put("machine", snapshot.taskMachineName?.take(100)).put("finish", snapshot.taskEstimatedFinishAtMillis)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("snapshot", value.toString()).commit()
        refresh(context)
    }

    @Synchronized internal fun read(context: Context): LaundryWidgetSnapshot? {
        if (!PushRuntime.isSessionActive(context)) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("snapshot", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            LaundryWidgetSnapshot(json.getString("building"), json.getInt("idle"), json.getInt("busy"),
                json.getInt("unknown"), json.getLong("updated"),
                if (json.isNull("machine")) null else json.getString("machine"),
                if (json.isNull("finish")) null else json.getLong("finish"))
        }.getOrNull()
    }

    @Synchronized fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit(); refresh(context) }
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, LaundryWidgetProvider::class.java))
        LaundryWidgetProvider().onUpdate(context, manager, ids)
    }
}

class LaundryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = LaundryWidgetStore.read(context)
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.laundry_widget)
            views.setTextViewText(R.id.widget_building, snapshot?.buildingName ?: "WeShirt")
            views.setTextViewText(R.id.widget_summary, snapshot?.let { "空闲 ${it.idle} · 使用 ${it.busy} · 未知 ${it.unknown}" }
                ?: "登录并选择宿舍楼后显示洗衣状态")
            val stale = snapshot == null || System.currentTimeMillis() - snapshot.updatedAtMillis > 10 * 60_000L
            views.setTextViewText(R.id.widget_updated, snapshot?.let {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.updatedAtMillis))
                if (stale) "$time 更新 · 请打开刷新" else "$time 更新"
            } ?: "点按打开应用")
            val finish = snapshot?.taskEstimatedFinishAtMillis
            val countdown = finish != null && finish > System.currentTimeMillis() && !stale
            views.setViewVisibility(R.id.widget_countdown, if (countdown) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_task, if (snapshot?.taskMachineName != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_task, snapshot?.taskMachineName?.let {
                if (finish != null && finish <= System.currentTimeMillis()) "$it · 预计时间已到，待确认" else "$it · 预计剩余"
            } ?: "")
            if (countdown) {
                views.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime() + finish!! - System.currentTimeMillis(), null, true)
                views.setChronometerCountDown(R.id.widget_countdown, true)
            }
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                intent.putExtra("weshirt.destination", "MY_LAUNDRY")
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(id, views)
        }
    }
}
