package com.laoke.weshirt.data

import org.junit.Assert.*
import org.junit.Test

class InspectionRoundTest {
    private val machines = setOf("one", "two")
    @Test fun allNormalNeedsNoPhotoOrRemarkAndOnlySelectedExceptionsAreUploaded() {
        val normal = InspectionRound.body("building", machines, emptyList(), "", "")
        assertEquals(0, normal.getJSONArray("issues").length())
        assertFalse(normal.has("photoData"))
        assertFalse(normal.has("machineId"))
        val exception = InspectionRound.body("building", machines, listOf(InspectionIssue("two", setOf("power_off", "offline"), " 插座异常 ")), " 本轮检查 ", "")
        val entry = exception.getJSONArray("issues").getJSONObject(0)
        assertEquals("two", entry.getString("machineId"))
        assertEquals(listOf("offline", "power_off"), (0..1).map { entry.getJSONArray("reasons").getString(it) })
        assertEquals("插座异常", entry.getString("note"))
        assertEquals("本轮检查", exception.getString("note"))
        assertEquals(1, exception.getJSONArray("issues").length())
    }
    @Test fun everySelectedMachineRequiresKnownReasonsAndMustBelongToThisBuilding() {
        val invalid = listOf(listOf(InspectionIssue("one")), listOf(InspectionIssue("outside", setOf("offline"))), listOf(InspectionIssue("one", setOf("unknown"))), listOf(InspectionIssue("one", setOf("damaged")), InspectionIssue("one", setOf("other"))))
        invalid.forEach { issues ->
            val error = runCatching { InspectionRound.body("building", machines, issues, "", "") }.exceptionOrNull() as ApiException
            assertEquals(422, error.status); assertEquals("invalid_inspection", error.code); assertTrue(error.eventId.startsWith("client_"))
        }
    }
    @Test fun optionalPanoramaStaysAttachedToTheWholeRound() {
        val body = InspectionRound.body("building", machines, emptyList(), "", "data:image/jpeg;base64,example")
        assertEquals("data:image/jpeg;base64,example", body.getString("photoData"))
        assertEquals(0, body.getJSONArray("issues").length())
    }
}
