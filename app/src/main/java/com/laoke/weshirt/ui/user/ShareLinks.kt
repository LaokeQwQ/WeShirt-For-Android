package com.laoke.weshirt.ui.user

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Share only server-issued, signed tickets. Login and MCP credentials never enter links. */
internal object ShareLinks {
    private val ticketPattern = Regex("^[A-Za-z0-9_-]+\\.[a-f0-9]{64}$")

    fun ticket(raw: String, parameter: String): String? {
        if (parameter !in setOf("ticket", "skill") || raw.length > 12_000) return null
        val value = raw.trim()
        if (ticketPattern.matches(value) && value.length <= 10_000) return value
        return runCatching {
            val uri = URI(value)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            val queries = listOfNotNull(uri.rawQuery, uri.rawFragment?.substringAfter('?', "")?.takeIf(String::isNotEmpty))
            val values = queries.flatMap { query -> query.split('&').mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8") == parameter) URLDecoder.decode(pair[1], "UTF-8") else null
            } }
            values.singleOrNull()?.takeIf { it.length <= 10_000 && ticketPattern.matches(it) }
        }.getOrNull()
    }

    fun social(endpoint: String, ticket: String) = build(endpoint, "social", "ticket", ticket)
    fun skill(endpoint: String, ticket: String) = build(endpoint, "assistant", "skill", ticket)

    private fun build(endpoint: String, page: String, parameter: String, ticket: String): String {
        require(ticketPattern.matches(ticket) && ticket.length <= 10_000) { "分享凭证格式不正确，请重新生成" }
        val origin = URI(endpoint)
        require(origin.scheme == "https" && !origin.host.isNullOrBlank() && origin.userInfo == null && origin.query == null && origin.fragment == null && origin.path in listOf("", "/"))
        return endpoint.trimEnd('/') + "/#/$page?$parameter=" + URLEncoder.encode(ticket, "UTF-8")
    }
}
