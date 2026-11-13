package com.laoke.weshirt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryTest {
    @Test fun cachedProfileRequiresMatchingOriginAccountAndCredentials() {
        val saved = JSONObject().put("origin", "https://example.invalid").put("accessToken", "saved")
            .put("user", JSONObject().put("id", "one").put("nick", "登录昵称"))
            .put("profile", JSONObject().put("id", "one").put("nick", "完整资料"))
        assertEquals("完整资料", cachedSessionProfile(saved, "https://example.invalid")?.str("nick"))
        assertNull(cachedSessionProfile(saved, "https://other.invalid"))
        assertNull(cachedSessionProfile(JSONObject(saved.toString()).put("accessToken", ""), "https://example.invalid"))
        assertNull(cachedSessionProfile(JSONObject(saved.toString()).put("user", JSONObject().put("id", "two")), "https://example.invalid"))
        cachedSessionProfile(saved, "https://example.invalid")!!.put("nick", "外部修改")
        assertEquals("完整资料", saved.getJSONObject("profile").str("nick"))
    }

    @Test fun newAssistantTabKeepsExistingNavigationIndicesAndUsesItsOwnTelemetryRoute() {
        assertEquals(listOf("/", "/task", "/board", "/settings", "/assistant"), (0..4).map(::nativeTabRoute))
    }

    @Test fun testNotificationTargetsOnlyThisInstallationAndNeverClaimsDelivery() {
        val body = mobileTestNotificationBody("device-one")
        assertEquals(setOf("channel", "installationId"), body.keys().asSequence().toSet())
        assertEquals("mobile", body.str("channel"))
        assertEquals("device-one", body.str("installationId"))
        val accepted = mobileTestNotificationMessage(JSONObject().put("accepted", true).put("provider", "fcm"))
        assertTrue(accepted.contains("FCM")); assertTrue(accepted.contains("不代表"))
        assertFalse(accepted.contains("钉钉"))
        val missing = mobileTestNotificationMessage(JSONObject())
        assertTrue(missing.contains("尚未接受")); assertFalse(missing.contains("已送达"))
    }
}
