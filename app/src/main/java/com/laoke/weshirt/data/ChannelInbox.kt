package com.laoke.weshirt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Memory only, isolated by service, user and login generation. Never stores message bodies. */
data class ChannelAccountScope(val origin: String, val userId: String, val generation: Int)

class ChannelInbox(private val api: ApiClient, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Mutex()
    private var account: ChannelAccountScope? = null
    private var channels: List<Channel> = emptyList()
    private var expires = 0L

    fun clear() { account = null; channels = emptyList(); expires = 0 }
    fun invalidate() { expires = 0 }

    suspend fun load(scope: ChannelAccountScope, current: () -> Boolean): List<Channel> = lock.withLock {
        if (!current()) throw CancellationException("Account changed")
        if (account == scope && now() < expires) return@withLock channels
        val rows = api.call("/api/messages/channels").optJSONArray("channels")?.objects().orEmpty().map(Channel::parse)
        if (!current()) throw CancellationException("Account changed")
        account = scope; channels = rows; expires = now() + 10_000
        rows
    }

    suspend fun acknowledge(scope: ChannelAccountScope, channel: String, ids: List<String>, current: () -> Boolean): Int? = lock.withLock {
        if (!current()) throw CancellationException("Conversation changed")
        val selected = ids.filter(String::isNotBlank).distinct().take(50)
        if (selected.isEmpty()) return@withLock null
        val response = api.call("/api/messages/read", "POST", JSONObject().put("channel", channel).put("messageIds", JSONArray(selected)))
        if (!current()) throw CancellationException("Conversation changed")
        val unread = response.optInt("unreadCount").coerceAtLeast(0)
        if (account == scope) channels = channels.map { if (it.code == channel) it.copy(unreadCount = unread) else it }
        unread
    }
}

/** Lazy item keys include headers; only actual incoming messages in the viewport qualify. */
fun visibleIncomingMessageIds(messages: List<Message>, visibleKeys: List<Any>): List<String> {
    val incoming = messages.asSequence().filter { !it.mine && it.id.isNotBlank() }.map { it.id }.toHashSet()
    return visibleKeys.filterIsInstance<String>().filter { it in incoming }.distinct().take(50)
}

/** The caller owns a RESUMED conversation lifecycle; a working relay needs no HTTP polling. */
internal suspend fun channelHttpFallback(current: () -> Boolean, connected: () -> Boolean, refresh: () -> Unit) {
    while (currentCoroutineContext().isActive) {
        delay(30_000)
        if (current() && !connected()) refresh()
    }
}

/** Require meaningful exposure rather than a one-pixel row at a clipped viewport edge. */
internal fun isMessageVisible(start: Int, size: Int, viewportStart: Int, viewportEnd: Int, minimumPixels: Int): Boolean {
    if (size <= 0 || viewportEnd <= viewportStart) return false
    val visible = (minOf(start + size, viewportEnd) - maxOf(start, viewportStart)).coerceAtLeast(0)
    val required = minOf(kotlin.math.ceil(size * .65).toInt(), minimumPixels).coerceAtLeast(1)
    return visible >= required
}
