package com.laoke.weshirt.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

/** The relay carries invalidations only. Bodies always come from the authenticated API. */
class ChannelRealtime(private val api: ApiClient) {
    suspend fun run(channel: String, changed: () -> Unit, status: (Boolean) -> Unit) = coroutineScope {
        require(channel.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        var attempt = 0
        while (isActive) {
            var socket: WebSocket? = null
            var heartbeat: Job? = null
            // Ready/closed are control events, not replaceable invalidations. A relay
            // may send ready and changed before the consumer starts receiving.
            val events = Channel<String>(32)
            fun enqueue(event: String, connection: WebSocket) {
                if (events.trySend(event).isFailure) {
                    // Bound memory under a noisy relay and reconnect instead of
                    // silently dropping a readiness or termination transition.
                    connection.cancel()
                    events.close()
                }
            }
            try {
                status(false)
                val config = api.call("/api/messages/realtime?channel=${api.encoded(channel)}")
                if (!config.optBoolean("enabled")) { delay(120_000); continue }
                val endsAt = config.optLong("expiresAt")
                socket = api.channelSocket(config, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (response.header("Sec-WebSocket-Protocol") != "weshirt.channel.v1") { webSocket.cancel(); enqueue("closed", webSocket); return }
                        webSocket.send(JSONObject().put("type", "authenticate").put("ticket", config.str("ticket")).toString())
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val payload = if (text.length <= 4096) runCatching { JSONObject(text) }.getOrNull() else null
                        val type = payload?.str("type")
                        if (type == "pong") return
                        if (payload?.str("channel") != channel || type !in setOf("ready", "channel.changed")) { webSocket.cancel(); enqueue("closed", webSocket); return }
                        enqueue(type!!, webSocket)
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { enqueue("closed", webSocket) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { enqueue("closed", webSocket) }
                })
                heartbeat = launch { while (isActive) { delay(25_000); socket?.send("{\"type\":\"ping\"}") } }
                withTimeout((endsAt - System.currentTimeMillis()).coerceIn(1, 120_000)) {
                    while (isActive) {
                        when (events.receive()) {
                            "closed" -> break
                            "ready" -> { attempt = 0; status(true); changed() }
                            "channel.changed" -> changed()
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) { /* Ticket expiry reconnects with a new credential. */ }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Visible-page HTTP fallback remains active. */ }
            finally { heartbeat?.cancel(); events.close(); socket?.cancel(); status(false) }
            delay(min(60_000L, 3_000L shl min(attempt++, 4)))
        }
    }
}
