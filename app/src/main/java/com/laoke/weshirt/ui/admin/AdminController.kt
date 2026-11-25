package com.laoke.weshirt.ui.admin

import androidx.compose.runtime.*
import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.ApiException
import kotlinx.coroutines.*
import org.json.JSONObject

/** Screen-local data only. No saved state, credential store, polling, or second API session. */
internal class AdminController(val api: ApiClient, private val scope: CoroutineScope, private val current: () -> Boolean) {
    var document by mutableStateOf(JSONObject()); private set
    var loadedPath by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var submitting by mutableStateOf(false); private set
    private var reconciling by mutableStateOf(false)
    val saving get() = submitting || reconciling
    var error by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<String?>(null); private set
    private var reader: Job? = null
    private var writer: Job? = null
    private var revision = 0L
    private var closed = false
    private fun active() = !closed && current()
    fun load(path: String, keepVisible: Boolean = false) {
        if (saving || !active()) return
        reader?.cancel()
        val request = ++revision
        if (!keepVisible || loadedPath != path) document = JSONObject()
        loadedPath = path; loading = true; error = null; info = null
        reader = scope.launch {
            try { val result = api.call(path); if (active() && request == revision) document = result }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active() && request == revision) applyReadError(e) }
            finally { if (active() && request == revision) loading = false }
        }
    }
    fun act(path: String, method: String = "POST", body: JSONObject = JSONObject(), refresh: String? = loadedPath, finished: () -> Unit = {}, success: (JSONObject) -> Unit = {}, key: String? = null) {
        if (saving || !active()) return
        reader?.cancel(); ++revision; loading = false; submitting = true; error = null; info = null
        writer = scope.launch {
            try {
                val result = api.call(path, method, body, key = key)
                if (!active()) return@launch
                success(result)
                info = "操作已完成"
                if (!refresh.isNullOrBlank()) {
                    // The write has completed. Keep controls on this panel protected until
                    // its data reloads, but allow the user to navigate away immediately.
                    reconciling = true
                    submitting = false
                    try {
                        val fresh = api.call(refresh)
                        if (active()) {
                            loadedPath = refresh
                            document = fresh
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (active()) applyReadError(e) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) error = safeError(e) }
            finally {
                finished()
                if (active()) { submitting = false; reconciling = false }
            }
        }
    }
    fun localError(message: String) { error = message.take(240) }
    fun inspect(path: String, result: (JSONObject) -> Unit) {
        if (saving || loading || !active()) return
        reader?.cancel(); val request = ++revision; loading = true; error = null
        reader = scope.launch {
            try { val value = api.call(path); if (active() && request == revision) result(value) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active() && request == revision) error = safeError(e) }
            finally { if (active() && request == revision) loading = false }
        }
    }
    fun exportCsv(path: String, write: suspend (String) -> Unit) {
        if (saving || loading || !active()) return
        submitting = true; error = null; info = null
        writer = scope.launch {
            try {
                val csv = api.adminCsv(path)
                ensureActive()
                if (!active()) return@launch
                write(csv)
                if (active()) info = "已导出到所选文件"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) error = safeError(e) }
            finally { if (active()) submitting = false }
        }
    }
    fun close() { closed = true; ++revision; reader?.cancel(); writer?.cancel(); document = JSONObject(); error = null; info = null }
    private fun applyReadError(problem: Exception) {
        if (problem is ApiException && problem.status in setOf(401, 403)) document = JSONObject()
        error = safeError(problem)
    }
    private fun safeError(error: Exception) = when (error) {
        is ApiException -> error.message ?: "请求失败 · 状态码 ${error.status} · 事件 ${error.eventId}"
        else -> "请求未完成，请重试\n状态码 0 · 事件 ${java.util.UUID.randomUUID()}"
    }
}

internal fun JSONObject.copyObject() = JSONObject(toString())
internal fun JSONObject.arrayRows(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()
internal fun JSONObject.text(key: String) = optString(key, "").takeUnless { it == "null" }.orEmpty()
