package com.laoke.weshirt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceEndpointTest {
    @Test fun releaseAlwaysUsesTheBundledServiceDespiteAnOldCustomPreference() {
        assertEquals("https://bundled.invalid", serviceEndpoint(false, "https://bundled.invalid", "https://old-custom.invalid"))
        assertEquals("", serviceEndpoint(false, "", "https://old-custom.invalid"))
    }

    @Test fun debugCanUseAnIsolatedFixtureOrTheBundledDefault() {
        assertEquals("https://fixture.invalid", serviceEndpoint(true, "https://bundled.invalid", "https://fixture.invalid"))
        assertEquals("", serviceEndpoint(true, "https://bundled.invalid", ""))
        assertEquals("https://bundled.invalid", serviceEndpoint(true, "https://bundled.invalid", null))
    }
}
