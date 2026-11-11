package com.laoke.weshirt.ui

import com.laoke.weshirt.ui.user.ShareLinks
import org.junit.Assert.*
import org.junit.Test

class ShareLinksTest {
    private val signed = "eyJ0eXBlIjoic29jaWFsLWludml0ZSJ9." + "a".repeat(64)

    @Test fun nativeInvitesUseACompleteWebTicketUrl() {
        val url = ShareLinks.social("https://laundry.example.org", signed)
        assertEquals("https://laundry.example.org/#/social?ticket=$signed", url)
        assertEquals(signed, ShareLinks.ticket(url, "ticket"))
        assertNull(ShareLinks.ticket(url, "skill"))
        assertEquals(signed, ShareLinks.ticket(signed, "ticket"))
    }

    @Test fun skillLinksImportOnlyTheSignedTemplateTicket() {
        val url = ShareLinks.skill("https://laundry.example.org/", signed)
        assertEquals("https://laundry.example.org/#/assistant?skill=$signed", url)
        assertEquals(signed, ShareLinks.ticket(url, "skill"))
        assertNull(ShareLinks.ticket("https://laundry.example.org/#/assistant?token=private", "skill"))
        assertNull(ShareLinks.ticket("Bearer private-login-token", "ticket"))
    }

    @Test fun ambiguousMalformedAndUnsafeLinksAreRejected() {
        assertNull(ShareLinks.ticket("http://laundry.example.org/?ticket=$signed", "ticket"))
        assertNull(ShareLinks.ticket("https://user:password@laundry.example.org/?ticket=$signed", "ticket"))
        assertNull(ShareLinks.ticket("https://laundry.example.org/?ticket=$signed#/social?ticket=$signed", "ticket"))
        assertNull(ShareLinks.ticket("https://laundry.example.org/#/social?ticket=%xx", "ticket"))
        assertNull(ShareLinks.ticket("a".repeat(12_001), "ticket"))
        assertNull(ShareLinks.ticket(signed, "accessToken"))
    }
}
