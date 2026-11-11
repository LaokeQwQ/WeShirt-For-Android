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
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<String?>(null); private set
    private var reader: Job? = null
    private var writer: Job? = null
    private var revision = 0L
    private var closed = false
    private fun active() = !closed && current()
    fun load(path: String) {
        if (saving || !active()) return
        reader?.cancel()
        val request = ++revision
        loadedPath = path; document = JSONObject(); loading = true; error = null; info = null
        reader = scope.launch {
            try { val result = api.call(path); if (active() && request == revision) document = result }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active() && request == revision) error = safeError(e) }
            finally { if (active() && request == revision) loading = false }
        }
    }
    fun act(path: String, method: String = "POST", body: JSONObject = JSONObject(), refresh: String? = loadedPath, finished: () -> Unit = {}, success: (JSONObject) -> Unit = {}) {
        if (saving || !active()) return
        reader?.cancel(); ++revision; loading = false; saving = true; error = null; info = null
        writer = scope.launch {
            try {
                val result = api.call(path, method, body)
                if (!active()) return@launch
                success(result)
                info = "操作已完成"
                if (!refresh.isNullOrBlank()) { val fresh = api.call(refresh); if (active()) document = fresh }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) error = safeError(e) }
            finally { finished(); if (active()) saving = false }
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
        saving = true; error = null; info = null
        writer = scope.launch {
            try {
                val csv = api.adminCsv(path)
                ensureActive()
                if (!active()) return@launch
                write(csv)
                if (active()) info = "审计日志已导出到所选文件"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) error = safeError(e) }
            finally { if (active()) saving = false }
        }
    }
    fun close() { closed = true; ++revision; reader?.cancel(); writer?.cancel(); document = JSONObject(); error = null; info = null }
    private fun safeError(error: Exception) = when (error) {
        is ApiException -> error.message ?: "请求失败 · 状态码 ${error.status} · 事件 ${error.eventId}"
        else -> "请求未完成，请重试\n状态码 0 · 事件 ${java.util.UUID.randomUUID()}"
    }
}

internal fun JSONObject.copyObject() = JSONObject(toString())
internal fun JSONObject.arrayRows(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()
internal fun JSONObject.text(key: String) = optString(key, "").takeUnless { it == "null" }.orEmpty()
