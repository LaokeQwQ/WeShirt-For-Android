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
        assertEquals(WidgetFreshness.RECENT, LaundryWidgetState.freshness(snapshot(now - 600_000), now))
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
}
