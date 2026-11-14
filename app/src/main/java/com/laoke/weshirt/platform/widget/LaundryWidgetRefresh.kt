package com.laoke.weshirt.platform.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.laoke.weshirt.BuildConfig
import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.ApiException
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Building
import com.laoke.weshirt.data.LaundryTask
import com.laoke.weshirt.data.Profile
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.SessionStore
import com.laoke.weshirt.data.epoch
import com.laoke.weshirt.data.objects
import com.laoke.weshirt.data.parseBoardSnapshot
import com.laoke.weshirt.data.serviceEndpoint
import com.laoke.weshirt.data.sameSessionCredentials
import com.laoke.weshirt.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** All clients share the encrypted store, but ApiClient's generation is local to
 * its instance. CAS stops a late widget refresh/401 from overwriting a new login. */
internal class WidgetSessionStore(
    private val sessions: SecureSession,
    initial: JSONObject,
    private val originCurrent: () -> Boolean,
) : SessionStore {
    override val installationId get() = sessions.installationId
    private var expected: JSONObject? = JSONObject(initial.toString())
    @Synchronized override fun read(): JSONObject? {
        val current = sessions.read()
        if (!originCurrent() || !sameSessionCredentials(current, expected)) throw CancellationException("Widget session changed")
        expected = current?.let { JSONObject(it.toString()) }
        return expected?.let { JSONObject(it.toString()) }
    }
    @Synchronized override fun save(value: JSONObject) {
        if (!originCurrent() || !sessions.compareAndSetCredentials(expected, value)) throw CancellationException("Widget credentials changed")
        expected = JSONObject(value.toString())
    }
    @Synchronized override fun clear() {
        if (!originCurrent() || !sessions.compareAndSetCredentials(expected, null)) throw CancellationException("Widget credentials changed")
        expected = null
    }
}

/** Inject only GETs; no notification, queue, admin or chat endpoint is available. */
internal suspend fun readVerifiedWidgetState(
    expectedAccount: String,
    request: suspend (path: String, auth: Boolean) -> JSONObject,
    current: () -> Boolean,
    now: () -> Long = System::currentTimeMillis,
): AppState {
    fun checkCurrent() { if (!current()) throw CancellationException("Widget account changed") }
    suspend fun read(path: String, auth: Boolean = true): JSONObject {
        checkCurrent()
        val result = request(path, auth)
        checkCurrent()
        return result
    }
    val user = read("/api/auth/me").optJSONObject("user")
        ?: throw widgetProblem(502, "profile_missing", "账号资料暂不可用，请重试")
    if (expectedAccount.isBlank() || user.str("id") != expectedAccount) throw CancellationException("Widget account differs from server")
    val access = read("/api/user/permissions")
    val permissions = access.optJSONObject("permissions")
        ?: throw widgetProblem(502, "permissions_missing", "权限暂不可用，请重试")
    val site = read("/api/site", false)
    val buildings = site.optJSONArray("buildings")?.objects().orEmpty()
        .filter { !it.has("enabled") || it.optBoolean("enabled") }
        .map { Building(it.str("code"), it.str("name")) }
    val building = user.str("dormBuilding")
    if (!Regex("[a-z][a-z0-9_-]{0,63}").matches(building) || buildings.none { it.code == building })
        throw widgetProblem(409, "building_required", "请在应用内选择已启用的住宿楼")
    val (board, tasks) = coroutineScope {
        val board = async { read("/api/machines?dorm=$building", false) }
        val tasks = async {
            if (permissions.optJSONObject("tasks")?.optBoolean("view", true) != false) read("/api/tasks")
            else JSONObject().put("tasks", JSONArray())
        }
        board.await() to tasks.await()
    }
    checkCurrent()
    val snapshot = parseBoardSnapshot(board, building)
    val offset = epoch(tasks.str("serverNow"))?.minus(now()) ?: 0L
    val ownTasks = tasks.optJSONArray("tasks")?.objects().orEmpty()
        // This endpoint is already restricted by the user's Bearer token; older
        // responses omit userId. Reject explicit foreign owners without breaking it.
        .filter { it.str("userId").let { owner -> owner.isBlank() || owner == expectedAccount } }
        .map { LaundryTask.parse(it).copy(clockOffsetMs = offset) }
    return AppState(building = building, buildings = buildings, profile = Profile.parse(user), access = access,
        authReady = true, tasks = ownTasks, machines = snapshot.machines, updatedAt = snapshot.updatedAt,
        boardWarning = snapshot.warning, tasksUpdatedAt = now(), tasksRefreshFailed = false)
}

private fun widgetProblem(status: Int, code: String, text: String) =
    ApiException(status, code, "client_" + UUID.randomUUID().toString().replace("-", ""), text)

/** Never persist arbitrary provider/body text on the home screen. */
internal fun widgetRefreshError(error: Exception): String {
    val status = (error as? ApiException)?.status?.takeIf { it in 0..599 } ?: 0
    val event = (error as? ApiException)?.eventId?.takeIf { Regex("(?:evt|client)_[A-Za-z0-9_-]{1,48}").matches(it) }
        ?: "client_" + UUID.randomUUID().toString().replace("-", "")
    return "刷新失败 · HTTP $status · 事件 $event"
}

internal object WidgetRefreshPolicy {
    const val CLICK_COOLDOWN_MILLIS = 30_000L
    const val FRESH_MILLIS = 10 * 60_000L
    fun mayRefresh(now: Long, previous: Long) = previous <= 0 || now < previous || now - previous >= CLICK_COOLDOWN_MILLIS
    fun tickDelay(snapshot: LaundryWidgetSnapshot?, now: Long): Long? {
        snapshot ?: return null
        if (snapshot.taskMachineName.isNullOrBlank() || snapshot.taskEstimatedFinishAtMillis?.takeIf { it > 0 } == null || snapshot.taskUnverified || snapshot.taskCompletionConfirmed) return null
        val age = now - snapshot.updatedAtMillis
        if (snapshot.updatedAtMillis <= 0 || age < -30_000L || age >= FRESH_MILLIS) return null
        // A bounded chain redraws the existing estimate only; reaching zero is
        // awaiting platform confirmation, never a synthetic idle/completed state.
        return minOf(60_000L, FRESH_MILLIS - age.coerceAtLeast(0)).coerceAtLeast(1)
    }
}

/** Ordinary work needs no notification or exact-alarm permission. OS/Doze may
 * delay execution; this is not a promise of minute-accurate background updates. */
object LaundryWidgetRefresh {
    internal const val TAG = "laundry_widget_work"
    internal const val REFRESH_NAME = "laundry_widget_refresh"
    internal const val TICK_NAME = "laundry_widget_tick"
    private const val PREFS = "laundry_widget_refresh_runtime"

    @Synchronized fun enqueue(context: Context, widgetId: Int) {
        val app = context.applicationContext
        if (widgetId !in installedIds(app)) return
        val scope = LaundryWidgetStore.captureScope(app)
        if (scope == null) { LaundryWidgetStore.refresh(app); return }
        val request = OneTimeWorkRequestBuilder<LaundryWidgetRefreshWorker>()
            .setInputData(Data.Builder().putString("scope", scope.key).putLong("generation", generation(app)).build()).addTag(TAG).build()
        WorkManager.getInstance(app).enqueueUniqueWork(REFRESH_NAME, ExistingWorkPolicy.KEEP, request)
    }

    @Synchronized internal fun acquire(context: Context, scope: LaundryWidgetScope, now: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sameScope = prefs.getString("scope", null) == scope.key
        if (sameScope && !WidgetRefreshPolicy.mayRefresh(now, prefs.getLong("attempt", 0))) return false
        return prefs.edit().putString("scope", scope.key).putLong("attempt", now).commit()
    }

    fun scheduleTicks(context: Context) = scheduleTick(context.applicationContext, append = false)
    internal fun scheduleTick(context: Context, append: Boolean) {
        val scope = LaundryWidgetStore.captureScope(context) ?: return
        if (installedIds(context).isEmpty()) return
        val snapshot = LaundryWidgetStore.read(context)
        val delay = WidgetRefreshPolicy.tickDelay(snapshot, System.currentTimeMillis()) ?: return
        val request = OneTimeWorkRequestBuilder<LaundryWidgetTickWorker>()
            .setInputData(Data.Builder().putString("scope", scope.key).putLong("generation", generation(context)).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(TICK_NAME,
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    @Synchronized fun cancel(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Invalidate running reads synchronously; WorkManager cancellation itself
        // is asynchronous, and logout may not have cleared SecureSession yet.
        prefs.edit().putLong("generation", generation(context) + 1L).remove("scope").remove("attempt").commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
    internal fun generation(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("generation", 0L)
    internal fun installedIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, LaundryWidgetProvider::class.java))
    internal fun origin(context: Context) = serviceEndpoint(BuildConfig.DEBUG, BuildConfig.API_BASE_URL,
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("endpoint", null)).trimEnd('/')
}

class LaundryWidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (LaundryWidgetRefresh.installedIds(applicationContext).isEmpty()) return Result.success()
        val scope = LaundryWidgetStore.captureScope(applicationContext) ?: return Result.success()
        if (inputData.getString("scope") != scope.key) return Result.success()
        val generation = inputData.getLong("generation", -1L)
        if (generation != LaundryWidgetRefresh.generation(applicationContext)) return Result.success()
        if (!LaundryWidgetRefresh.acquire(applicationContext, scope, System.currentTimeMillis())) return Result.success()
        val sessions = SecureSession(applicationContext)
        val saved = sessions.read() ?: return Result.success()
        val origin = LaundryWidgetRefresh.origin(applicationContext)
        if (origin.isBlank() || saved.str("origin") != origin) return Result.success()
        val bound = WidgetSessionStore(sessions, saved) { LaundryWidgetRefresh.origin(applicationContext) == origin && LaundryWidgetRefresh.generation(applicationContext) == generation }
        val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        val api = ApiClient(bound, applicationContext.cacheDir, client).also { it.baseUrl = origin }
        fun current() = !isStopped && LaundryWidgetRefresh.generation(applicationContext) == generation && LaundryWidgetStore.captureScope(applicationContext) == scope && LaundryWidgetRefresh.origin(applicationContext) == origin
        try {
            val state = withTimeout(45_000L) { readVerifiedWidgetState(scope.userId, { path, auth -> api.call(path, auth = auth) }, ::current) }
            val snapshot = projectLaundryWidget(state, System.currentTimeMillis())
                ?: throw widgetProblem(502, "widget_snapshot_missing", "暂无可显示的洗衣房状态，请重试")
            synchronized(LaundryWidgetRefresh) {
                if (current() && state.access.optJSONObject("permissions")?.optJSONObject("tasks")?.optBoolean("view") != true)
                    LaundryWidgetStore.redactTask(applicationContext, scope)
                if (current() && LaundryWidgetStore.update(applicationContext, snapshot, scope)) LaundryWidgetRefresh.scheduleTicks(applicationContext)
            }
        } catch (_: TimeoutCancellationException) {
            synchronized(LaundryWidgetRefresh) {
                if (current()) LaundryWidgetStore.setError(applicationContext, widgetRefreshError(widgetProblem(0, "widget_timeout", "刷新超时，请稍后重试")), scope)
            }
        } catch (cancel: CancellationException) {
            // CAS/profile/session conflicts discard the obsolete client. System
            // cancellation remains cancellation, not a synthetic network error.
            if (isStopped) throw cancel
            return Result.success()
        } catch (error: Exception) {
            synchronized(LaundryWidgetRefresh) {
                if (current()) {
                    val safe = if (error is ApiException) error else widgetProblem(0, "widget_network", "暂时无法连接，请稍后重试")
                    LaundryWidgetStore.setError(applicationContext, widgetRefreshError(safe), scope)
                }
            }
        } finally {
            if (!isStopped && generation == LaundryWidgetRefresh.generation(applicationContext) && !current()) LaundryWidgetStore.refresh(applicationContext)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        return Result.success()
    }
}

class LaundryWidgetTickWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (isStopped) return Result.success()
        val scope = LaundryWidgetStore.captureScope(applicationContext) ?: return Result.success()
        if (inputData.getString("scope") != scope.key || inputData.getLong("generation", -1L) != LaundryWidgetRefresh.generation(applicationContext) || LaundryWidgetRefresh.installedIds(applicationContext).isEmpty()) return Result.success()
        // Redraw never rewrites the snapshot timestamp or makes an API request.
        synchronized(LaundryWidgetRefresh) {
            if (!isStopped && inputData.getLong("generation", -1L) == LaundryWidgetRefresh.generation(applicationContext)) {
                LaundryWidgetStore.refresh(applicationContext)
                LaundryWidgetRefresh.scheduleTick(applicationContext, append = true)
            }
        }
        return Result.success()
    }
}
