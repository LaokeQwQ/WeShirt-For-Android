package com.laoke.weshirt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

data class AssistantScope(val origin: String, val userId: String, val generation: Int)
data class AssistantPanel(val data: JSONObject? = null, val loading: Boolean = false, val error: String? = null, val updatedAt: Long? = null)
data class AssistantSnapshot(val scope: AssistantScope? = null, val panels: Map<String, AssistantPanel> = emptyMap())

/** Memory only. The caller must still verify its current authenticated session before each publication. */
class AssistantPreloader(
    private val request: suspend (String) -> JSONObject,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry(val panel: AssistantPanel, val until: Long)
    private val entries = mutableMapOf<Pair<AssistantScope, String>, Entry>()
    private val locks = mutableMapOf<Pair<AssistantScope, String>, Mutex>()
    private val revisions = mutableMapOf<Pair<AssistantScope, String>, Long>()
    private val permits = mutableMapOf<AssistantScope, Semaphore>()
    private var epoch = 0L
    fun clear() { epoch++; entries.clear(); locks.clear(); revisions.clear(); permits.clear() }
    fun invalidate(scope: AssistantScope, names: Set<String> = NAMES) {
        names.forEach { name ->
            val key = scope to name
            revisions[key] = (revisions[key] ?: 0) + 1
            entries[key]?.let { entries[key] = it.copy(until = 0) }
        }
    }
    /** A confirmed write supersedes every older GET, even if its reconciliation later fails. */
    fun accept(scope: AssistantScope, name: String, value: JSONObject): AssistantPanel {
        require(name in NAMES)
        val key = scope to name
        revisions[key] = (revisions[key] ?: 0) + 1
        val panel = AssistantPanel(JSONObject(value.toString()), updatedAt = now())
        entries[key] = Entry(panel, now() + if (name in setOf("status", "history")) 30_000 else 300_000)
        return copy(panel)
    }

    suspend fun load(scope: AssistantScope, names: Set<String> = NAMES, force: Boolean = false,
                     current: () -> Boolean, publish: (String, AssistantPanel) -> Unit) = coroutineScope {
        require(names.all { it in NAMES })
        val version = epoch
        val limit = permits.getOrPut(scope) { Semaphore(3) }
        names.map { name -> async {
            val key = scope to name
            val lock = locks.getOrPut(key) { Mutex() }
            val observed = entries[key]
            lock.withLock {
                fun checkCurrent() { if (epoch != version || !current()) throw CancellationException("Assistant account changed") }
                checkCurrent()
                val revision = revisions[key] ?: 0
                val cached = entries[key]
                // Even an explicit refresh joins a request already completed while it waited.
                if (cached != null && now() < cached.until && (!force || cached !== observed)) {
                    publish(name, copy(cached.panel)); return@withLock
                }
                publish(name, copy(cached?.panel ?: AssistantPanel()).copy(loading = true, error = null))
                try {
                    val value = limit.withPermit { checkCurrent(); request("/api/assistant/$name") }
                    checkCurrent()
                    if ((revisions[key] ?: 0) != revision) return@withLock
                    val panel = AssistantPanel(JSONObject(value.toString()), updatedAt = now())
                    entries[key] = Entry(panel, now() + if (name in setOf("status", "history")) 30_000 else 300_000)
                    publish(name, copy(panel))
                } catch (cancel: CancellationException) { throw cancel }
                catch (problem: Exception) {
                    checkCurrent()
                    if ((revisions[key] ?: 0) != revision) return@withLock
                    val panel = copy(cached?.panel ?: AssistantPanel()).copy(loading = false, error = problem.message ?: "资料暂未更新，请重试")
                    entries[key] = Entry(panel, now() + 5_000)
                    publish(name, copy(panel))
                }
            }
        } }.awaitAll()
    }

    private fun copy(panel: AssistantPanel) = panel.copy(data = panel.data?.let { JSONObject(it.toString()) })
    companion object { val NAMES = setOf("status", "history", "skills", "memory", "preferences") }
}
