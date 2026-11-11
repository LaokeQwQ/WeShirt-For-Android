package com.laoke.weshirt.data

import org.json.JSONObject

data class MachineDiscussionPage(val messages: List<Message>, val muted: Boolean, val hasMore: Boolean)

/** Machine comments are scoped messages in the existing dorm channel, with server authorization. */
class MachineDiscussion(private val api: ApiClient) {
    suspend fun load(channel: String, machineId: String, page: Int = 1): MachineDiscussionPage {
        require(channel.isNotBlank() && channel != "public" && machineId.isNotBlank() && page in 1..100)
        val result = api.call("/api/messages?channel=${api.encoded(channel)}&machineId=${api.encoded(machineId)}&limit=20&page=$page")
        return MachineDiscussionPage(result.optJSONArray("messages")?.objects().orEmpty().map(Message::parse),
            result.optJSONObject("mute")?.optBoolean("active") == true,
            result.optJSONObject("pagination")?.optBoolean("hasMore") == true)
    }

    suspend fun post(channel: String, machineId: String, text: String, replyTo: String? = null) {
        require(channel.isNotBlank() && channel != "public" && machineId.isNotBlank())
        val body = JSONObject().put("channel", channel).put("machineId", machineId).put("text", text.trim())
        if (!replyTo.isNullOrBlank()) body.put("replyTo", replyTo)
        api.call("/api/messages", "POST", body)
    }
}
