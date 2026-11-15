package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.widget.LaundryWidgetSnapshot
import com.laoke.weshirt.platform.widget.LaundryWidgetState
import com.laoke.weshirt.platform.widget.WidgetFreshness
import com.laoke.weshirt.platform.widget.WidgetSize
import com.laoke.weshirt.platform.widget.WidgetTaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class LaundryWidgetStateTest {
    private val now = 1_800_000_000_000L
    private fun snapshot(updated: Long = now, finish: Long? = now + 60_000) =
        LaundryWidgetSnapshot("Test building", 2, 4, 1, updated, "Washer 01", finish)

    @Test fun oldDataCannotEstablishCurrentTaskStatus() {
        val old = snapshot(now - 600_001)
        assertEquals(WidgetFreshness.OUTDATED, LaundryWidgetState.freshness(old, now))
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(old, now))
        assertEquals(WidgetFreshness.OUTDATED, LaundryWidgetState.freshness(snapshot(now - 600_000), now))
        assertEquals(WidgetFreshness.RECENT, LaundryWidgetState.freshness(snapshot(now - 599_999), now))
    }

    @Test fun missingAndImplausibleTimesNeverBecomeLiveCounts() {
        assertEquals(WidgetFreshness.MISSING, LaundryWidgetState.freshness(null, now))
        assertEquals(WidgetFreshness.INVALID_TIME, LaundryWidgetState.freshness(snapshot(0), now))
        assertEquals(WidgetFreshness.INVALID_TIME, LaundryWidgetState.freshness(snapshot(now + 30_001), now))
        assertEquals(WidgetFreshness.RECENT, LaundryWidgetState.freshness(snapshot(now + 30_000), now))
    }

    @Test fun reachingEstimateStillRequiresDeviceConfirmation() {
        assertEquals(WidgetTaskState.ESTIMATED, LaundryWidgetState.taskState(snapshot(finish = now + 1), now))
        assertEquals(WidgetTaskState.AWAITING_CONFIRMATION, LaundryWidgetState.taskState(snapshot(finish = now), now))
        assertEquals(WidgetTaskState.AWAITING_CONFIRMATION, LaundryWidgetState.taskState(snapshot(finish = now - 1), now))
    }

    @Test fun missingTaskOrFinishCannotProduceAnEstimatedFinish() {
        assertEquals(WidgetTaskState.NONE, LaundryWidgetState.taskState(null, now))
        assertEquals(WidgetTaskState.NONE, LaundryWidgetState.taskState(snapshot().copy(taskMachineName = " "), now))
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(snapshot(finish = null), now))
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(snapshot(finish = -1), now))
    }

    @Test fun compactAndLargeFontsPrioritizeReadableInformation() {
        assertEquals(WidgetSize.COMPACT, LaundryWidgetState.size(110, 110, 1f))
        assertEquals(WidgetSize.WIDE, LaundryWidgetState.size(250, 140, 1f))
        assertEquals(WidgetSize.EXPANDED, LaundryWidgetState.size(250, 340, 1f))
        assertEquals(WidgetSize.COMPACT, LaundryWidgetState.size(250, 340, 2f))
    }

    @Test fun externalNamesCannotInsertExtraRowsOrUnboundedText() {
        assertEquals("Washer 01", LaundryWidgetState.safeLabel("  Washer \n01\t  "))
        assertEquals(100, LaundryWidgetState.safeLabel("x".repeat(1000)).length)
    }

    @Test fun elapsedTimeAdvancesRealProgressButNeverTurnsTheTaskIntoIdle() {
        val known = snapshot(finish = now + 480_000).copy(taskStartedAtMillis = now - 480_000)
        assertEquals(50, LaundryWidgetState.progressPercent(known, now))
        assertEquals(75, LaundryWidgetState.progressPercent(known, now + 240_000))
        assertEquals(100, LaundryWidgetState.progressPercent(known, now + 480_000))
        assertEquals(WidgetTaskState.AWAITING_CONFIRMATION, LaundryWidgetState.taskState(known, now + 480_000))
        org.junit.Assert.assertNull(LaundryWidgetState.progressPercent(known.copy(taskStartedAtMillis = known.taskEstimatedFinishAtMillis), now))
    }

    @Test fun refreshFailureDoesNotTurnAnOldEmptyTaskListIntoIdle() {
        val empty = snapshot().copy(taskMachineName = null, taskEstimatedFinishAtMillis = null)
        assertEquals(WidgetTaskState.NONE, LaundryWidgetState.taskState(empty, now))
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(empty.copy(taskUnverified = true), now))
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(empty.copy(updatedAtMillis = now - 700_000), now))
    }
}
