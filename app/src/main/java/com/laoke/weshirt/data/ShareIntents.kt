package com.laoke.weshirt.data

import com.laoke.weshirt.ui.user.ShareLinks
import java.net.URI
import java.util.Locale

/** External intents cannot change the configured service or accept invitations implicitly. */
internal fun parseIncomingShare(raw: String, endpoint: String): PendingShare? = runCatching {
    require(raw.length <= 12_000 && endpoint.isNotBlank())
    val uri = URI(raw.trim())
    val expected = URI(endpoint)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.path in listOf("", "/") && uri.rawQuery == null)
    require(expected.scheme == "https" && expected.userInfo == null && expected.host != null)
    require(uri.host.lowercase(Locale.ROOT) == expected.host.lowercase(Locale.ROOT) && (if (uri.port == -1) 443 else uri.port) == (if (expected.port == -1) 443 else expected.port))
    val route = uri.fragment?.substringBefore('?')
    val kind = when (route) { "/social" -> "friend"; "/assistant" -> "skill"; else -> error("Unsupported share") }
    val ticket = requireNotNull(ShareLinks.ticket(raw, if (kind == "skill") "skill" else "ticket"))
    PendingShare(endpoint, kind, ticket)
}.getOrNull()
