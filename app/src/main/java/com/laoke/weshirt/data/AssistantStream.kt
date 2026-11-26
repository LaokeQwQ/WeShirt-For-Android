package com.laoke.weshirt.data

import org.json.JSONObject

internal sealed interface AssistantStreamEvent {
    data class Delta(val text: String) : AssistantStreamEvent
    data class Done(val result: JSONObject) : AssistantStreamEvent
    data object Reset : AssistantStreamEvent
}

/** Bounded SSE framing, independent of socket chunk/UTF-8 boundaries. No reconnect or replay. */
internal class AssistantSseDecoder(private val requestId: String) {
    private var event = "message"
    private val data = StringBuilder()
    private var hasData = false
    private var totalBytes = 0L
    private var answerLength = 0
    private var firstLine = true
    private var finished = false

    fun line(input: String): AssistantStreamEvent? {
        val line = if (firstLine) input.removePrefix("\uFEFF") else input
        firstLine = false
        totalBytes += input.toByteArray(Charsets.UTF_8).size + 1
        if (totalBytes > MAX_STREAM_BYTES) throw invalid("回答数据过大，请缩短问题后重试")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value); hasData = true
                if (data.length > MAX_FRAME_CHARS) throw invalid("回答数据过大，请缩短问题后重试")
            }
            // SSE id/retry are deliberately ignored: a paid POST is never replayed.
        }
        return null
    }

    fun end() {
        if (!finished) throw ApiException(502, "assistant_stream_interrupted", requestId, "回答连接已中断，已显示的内容尚未确认保存，请刷新聊天记录")
    }

    private fun dispatch(): AssistantStreamEvent? {
        val kind = event; event = "message"
        if (!hasData) return null
        val raw = data.toString(); data.clear(); hasData = false
        if (finished) throw invalid()
        val value = try { JSONObject(raw) } catch (_: Exception) { throw invalid() }
        return when (kind) {
            "reset" -> { answerLength = 0; AssistantStreamEvent.Reset }
            "delta" -> {
                val text = value.opt("text") as? String ?: throw invalid()
                answerLength += text.length
                if (answerLength > MAX_ANSWER_CHARS) throw invalid("回答过长，请缩短问题后重试")
                AssistantStreamEvent.Delta(text)
            }
            "done" -> {
                val reply = value.opt("reply") as? String ?: throw invalid()
                if (reply.isBlank() || reply.length > MAX_ANSWER_CHARS || value.optJSONArray("messages") == null) throw invalid()
                finished = true
                AssistantStreamEvent.Done(value)
            }
            "error" -> {
                finished = true
                val error = value.optJSONObject("error") ?: throw invalid()
                throw ApiException(error.optInt("status", 502).takeIf { it in 400..599 } ?: 502,
                    error.str("code", "assistant_stream_failed").take(80), error.str("eventId", requestId).ifBlank { requestId }.take(160),
                    error.str("message", "回答未完成，请稍后重试").take(1000))
            }
            else -> throw invalid()
        }
    }

    private fun invalid(message: String = "助手返回格式异常，请稍后重试") = ApiException(502, "assistant_stream_invalid", requestId, message)
    companion object {
        const val MAX_LINE_BYTES = 512L * 1024
        private const val MAX_FRAME_CHARS = 512 * 1024
        private const val MAX_STREAM_BYTES = 2L * 1024 * 1024
        private const val MAX_ANSWER_CHARS = 6000
    }
}
