package com.laoke.weshirt

import com.laoke.weshirt.data.parseIncomingShare
import org.junit.Assert.*
import org.junit.Test

class ShareIntentsTest {
    private val origin = "https://laundry.example"
    private val ticket = "YWJj." + "a".repeat(64)
    private fun link(path: String = "social", query: String = "ticket=$ticket") = "$origin/#/$path?$query"

    @Test fun acceptsFriendAndSkillOnCurrentServiceOnly() {
        assertEquals("friend", parseIncomingShare(link(), origin)?.kind)
        assertEquals(ticket, parseIncomingShare(link("assistant", "skill=$ticket"), origin)?.ticket)
        assertEquals("skill", parseIncomingShare(link("assistant", "skill=$ticket"), origin)?.kind)
    }
    @Test fun rejectsForeignOriginAndUnexpectedPortOrCredentials() {
        assertNull(parseIncomingShare(link().replace("laundry.example", "other.example"), origin))
        assertNull(parseIncomingShare(link().replace("laundry.example", "laundry.example:444"), origin))
        assertNull(parseIncomingShare(link().replace("laundry.example", "user@laundry.example"), origin))
        assertNull(parseIncomingShare(link().replace("https:", "http:"), origin))
    }
    @Test fun rejectsAmbiguousOrUnsupportedRouteAndMissingService() {
        assertNull(parseIncomingShare(link(query = "ticket=$ticket&ticket=$ticket"), origin))
        assertNull(parseIncomingShare(link("admin"), origin))
        assertNull(parseIncomingShare("$origin/redirect/#/social?ticket=$ticket", origin))
        assertNull(parseIncomingShare(link(), ""))
        assertNull(parseIncomingShare(ticket, origin))
    }
    @Test fun acceptsDefaultTlsPortAndHostnameCaseWithoutSwitchingOrigin() {
        val parsed = parseIncomingShare(link().replace("laundry.example", "LAUNDRY.example:443"), origin)
        assertEquals(origin, parsed?.origin)
    }
}
