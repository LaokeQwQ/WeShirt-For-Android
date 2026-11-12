package com.laoke.weshirt.data

import org.junit.Assert.*
import org.junit.Test

class TaskRegistrationTest {
    private fun machine(kind: String = "washer", state: String = "idle", finishAt: Long? = null) =
        Machine("machine-1", "测试机器", "1", kind, state, finishAt, "", false, false, "")

    @Test fun customWasherDurationsRemainAvailableAlongsideWebPrograms() {
        assertEquals(listOf(6, 30, 42, 50, 70), TaskRegistration.washerPrograms.map { it.minutes })
        assertEquals("self_clean", TaskRegistration.washerPrograms.first().code)
        for (minutes in listOf(1, 6, 25, 40, 180)) assertTrue(TaskRegistration.validMinutes("washer", minutes))
        for (minutes in listOf(0, 181)) assertFalse(TaskRegistration.validMinutes("washer", minutes))
    }

    @Test fun fortyMinuteDryingIsAcceptedAndNeverPublishesWasherCareFlags() {
        val body = TaskRegistration.body(machine("dryer"), 40, "  取衣前请确认  ", true, true, false, false)
        assertEquals(40, body.getInt("minutes"))
        assertEquals("取衣前请确认", body.getString("note"))
        assertFalse(body.getBoolean("useDisinfectant"))
        assertFalse(body.getBoolean("selfCleaned"))
        assertFalse(body.has("program"))
    }

    @Test fun sixMinuteSelfCleaningIsExplicitAndCustomSixDoesNotInventAProgram() {
        val clean = TaskRegistration.body(machine(), 6, "", false, true, false, true)
        assertEquals("self_clean", clean.getString("program"))
        assertEquals(6, clean.getInt("minutes"))
        val custom = TaskRegistration.body(machine(), 6, "", false, false, false, false)
        assertFalse(custom.has("program"))
        val problem = runCatching { TaskRegistration.body(machine(), 30, "", false, false, false, true) }.exceptionOrNull()
        assertTrue(problem is ApiException)
    }

    @Test fun machineTimingMustRemainActiveAndDoesNotSendAManualDuration() {
        val now = 1000L
        val body = TaskRegistration.body(machine(state = "occupied", finishAt = now + 120_000), 30, "公开备注", true, true, true, false, now)
        assertEquals("machine", body.getString("mode"))
        assertFalse(body.has("minutes"))
        assertTrue(body.getBoolean("useDisinfectant"))
        assertTrue(body.getBoolean("selfCleaned"))
        assertFalse(TaskRegistration.machineTimingAvailable(machine(finishAt = now + 1000), now))
        val problem = runCatching { TaskRegistration.body(machine(state = "occupied", finishAt = now), 30, "", false, false, true, false, now) }.exceptionOrNull() as ApiException
        assertEquals(422, problem.status)
        assertEquals("invalid_registration", problem.code)
        assertTrue(problem.eventId.startsWith("client_"))
    }

    @Test fun invalidDryerRangeAndOverlongNotesAreRejectedBeforeSubmitting() {
        for (minutes in listOf(0, 9, 181)) {
            val failure = runCatching { TaskRegistration.body(machine("dryer"), minutes, "", false, false, false, false) }.exceptionOrNull()
            assertTrue(failure is ApiException)
        }
        val failure = runCatching { TaskRegistration.body(machine(), 30, "字".repeat(201), false, false, false, false) }.exceptionOrNull()
        assertTrue(failure is ApiException)
    }

    @Test fun followingTheMachineUsesItsSnapshotClockAndRefusesReachedDeadlines() {
        val now = 1_000_000L
        for (offset in listOf(-600_000L, 600_000L)) {
            val active = machine(state = "occupied", finishAt = now + offset + 60_000).copy(clockOffsetMs = offset)
            assertTrue(TaskRegistration.machineTimingAvailable(active, now))
            assertEquals("machine", TaskRegistration.body(active, 30, "", false, false, true, false, now).getString("mode"))
            val reached = active.copy(finishAt = now + offset)
            assertFalse(TaskRegistration.machineTimingAvailable(reached, now))
            val failure = runCatching { TaskRegistration.body(reached, 30, "", false, false, true, false, now) }.exceptionOrNull() as ApiException
            assertEquals(422, failure.status)
            assertEquals("invalid_registration", failure.code)
        }
    }

    @Test fun estimatedUpstreamTimeAndSuspectedPickupParseWithoutBecomingMachineTiming() {
        val parsed = Machine.parse(org.json.JSONObject("""{"id":"test","state":"occupied","finishAt":"2030-01-01T00:00:00Z","estimated":true,"suspectedUncollected":true}"""))
        assertTrue(parsed.estimated); assertTrue(parsed.suspectedUncollected)
        assertFalse(TaskRegistration.machineTimingAvailable(parsed, 1000))
        val failure = runCatching { TaskRegistration.body(parsed, 30, "", false, false, true, false, 1000) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertTrue(TaskRegistration.machineTimingAvailable(parsed.copy(estimated = false), 1000))
    }
}
