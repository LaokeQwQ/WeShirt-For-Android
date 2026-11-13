package com.laoke.weshirt.data

import org.json.JSONObject

/** Cached display identity is valid only inside the same encrypted origin/account session. */
internal fun cachedSessionProfile(saved: JSONObject?, origin: String): JSONObject? {
    if (saved == null || saved.str("origin") != origin || saved.str("accessToken").isBlank()) return null
    val user = saved.optJSONObject("user")
    val profile = saved.optJSONObject("profile") ?: user ?: return null
    val id = profile.str("id")
    if (id.isBlank() || user?.str("id")?.takeIf(String::isNotBlank)?.let { it != id } == true) return null
    return JSONObject(profile.toString())
}

internal fun sessionWithProfile(value: JSONObject, origin: String, previous: JSONObject? = null): JSONObject {
    val next = JSONObject(value.toString()).put("origin", origin)
    val profile = cachedSessionProfile(previous, origin)
    val nextId = value.optJSONObject("user")?.str("id").orEmpty()
    if (profile != null && nextId.isNotBlank() && nextId == profile.str("id")) next.put("profile", profile)
    return next
}

internal fun nativeTabRoute(tab: Int): String = when (tab) {
    0 -> "/"
    1 -> "/task"
    2 -> "/board"
    4 -> "/assistant"
    else -> "/settings"
}

internal fun mobileTestNotificationBody(installationId: String) = JSONObject()
    .put("channel", "mobile").put("installationId", installationId)

internal fun mobileTestNotificationMessage(result: JSONObject): String = if (result.optBoolean("accepted")) {
    val provider = if (result.str("provider") == "fcm") "FCM" else if (result.str("provider") == "upush") "U-Push" else "推送服务商"
    "$provider 已接受本机测试请求，请检查系统通知；这不代表手机已送达。"
} else result.str("message", result.str("hint", "本机推送尚未接受，请检查通知权限和设备注册状态后重试。"))
