package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.login.DingTalkLoginBridge
import org.junit.Assert.*
import org.junit.Test

class DingTalkProofTest {
    @Test fun pkceS256MatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DingTalkLoginBridge.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun callbackStateMustMatchAndMustExist() {
        assertTrue(DingTalkLoginBridge.same("state", "state"))
        assertFalse(DingTalkLoginBridge.same("state", "different"))
        assertFalse(DingTalkLoginBridge.same(null, null))
        assertFalse(DingTalkLoginBridge.same("state", null))
    }
}
