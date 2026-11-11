package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.push.EventRevisionLedger
import com.laoke.weshirt.platform.push.NativePushEvent
import com.laoke.weshirt.platform.push.PushAvailability
import com.laoke.weshirt.platform.push.PushEventKind
import com.laoke.weshirt.platform.push.PushProvider
import com.laoke.weshirt.platform.push.PushRegion
import com.laoke.weshirt.platform.push.PushRouter
import com.laoke.weshirt.platform.push.TaskNoticeCoalescing
import com.laoke.weshirt.platform.push.TaskNoticeReceipt
import org.junit.Assert.*
import org.junit.Test

class PushRoutingTest {
    @Test fun noConsentDisablesEveryProviderEvenWithValidConfiguration() {
        assertEquals(PushProvider.NONE, PushRouter.choose(PushRegion.AUTO,
            PushAvailability(false, true, true, true)).provider)
    }

    @Test fun autoChoosesExactlyOneProviderAndRequiresWorkingGoogleServices() {
        assertEquals(PushProvider.FCM, PushRouter.choose(PushRegion.AUTO,
            PushAvailability(true, true, true, true)).provider)
        assertEquals(PushProvider.UMENG, PushRouter.choose(PushRegion.AUTO,
            PushAvailability(true, false, true, true)).provider)
        assertEquals(PushProvider.NONE, PushRouter.choose(PushRegion.GOOGLE,
            PushAvailability(true, false, true, true)).provider)
        assertEquals(PushProvider.NONE, PushRouter.choose(PushRegion.AUTO,
            PushAvailability(true, false, true, false)).provider)
        assertEquals(PushProvider.UMENG, PushRouter.choose(PushRegion.AUTO,
            PushAvailability(true, true, false, true)).provider)
    }

    @Test fun explicitDomesticNeverRegistersFcmInParallel() {
        assertEquals(PushProvider.UMENG, PushRouter.choose(PushRegion.DOMESTIC,
            PushAvailability(true, true, true, true)).provider)
        assertEquals(PushProvider.NONE, PushRouter.choose(PushRegion.DOMESTIC,
            PushAvailability(true, true, true, false)).provider)
        assertEquals("UMENG_CHANNEL", PushRouter.choose(PushRegion.DOMESTIC,
            PushAvailability(true, false, false, true)).reason)
    }

    @Test fun sameEventOnlyAcceptsNewerRevisions() {
        val ledger = EventRevisionLedger()
        ledger.record("task:one:finished", 3)
        assertFalse(ledger.shouldAccept("task:one:finished", 3))
        assertFalse(ledger.shouldAccept("task:one:finished", 2))
        assertTrue(ledger.shouldAccept("task:one:finished", 4))
        assertTrue(ledger.shouldAccept("task:two:finished", 0))
        assertFalse(ledger.shouldAccept("task:two:finished", -1))
    }

    @Test fun ledgerHasBoundedStorage() {
        val ledger = EventRevisionLedger(2)
        ledger.record("one", 1); ledger.record("two", 1); ledger.record("three", 1)
        assertEquals(setOf("two", "three"), ledger.entries().keys)
    }

    @Test fun estimatedTimersCannotClaimAuthoritativeCompletion() {
        assertFalse(NativePushEvent("e", 1, PushEventKind.TASK_FINISHED, "洗衣结束", "任务完成").valid())
        assertTrue(NativePushEvent("e", 1, PushEventKind.TASK_FINISHED, "洗衣结束", "设备已确认", serverConfirmed = true).valid())
        assertTrue(NativePushEvent("e", 1, PushEventKind.ESTIMATED_END, "预计时间已到", "等待设备确认").valid())
    }

    @Test fun localAndServerLeadForTheSameFinishOnlyDisplayOnce() {
        val local = NativePushEvent("local", 7, PushEventKind.QUEUE_THREE_MINUTES, "即将结束", "估计", "one", finishAtMillis = 900_000)
        val receipt = TaskNoticeReceipt(local.eventId, local.revision, local.finishAtMillis, false)
        assertFalse(TaskNoticeCoalescing.shouldDisplay(local.copy(eventId = "server", revision = 1), receipt))
        assertTrue(TaskNoticeCoalescing.shouldDisplay(local.copy(eventId = "server", finishAtMillis = 1_200_001), receipt))
        assertTrue(TaskNoticeCoalescing.shouldDisplay(local.copy(revision = 8), receipt))
        assertNotEquals(TaskNoticeCoalescing.key(local), TaskNoticeCoalescing.key(local.copy(taskId = "two")))
        assertNotEquals(TaskNoticeCoalescing.key(local), TaskNoticeCoalescing.key(local.copy(kind = PushEventKind.ESTIMATED_END)))
    }

    @Test fun confirmedEndReplacesEstimateAndCannotBeDowngradedByDelayedTimers() {
        val estimate = NativePushEvent("local-end", 7, PushEventKind.ESTIMATED_END, "预计结束", "等待确认", "one", finishAtMillis = 900_000)
        val receipt = TaskNoticeReceipt(estimate.eventId, estimate.revision, estimate.finishAtMillis, false)
        val confirmation = estimate.copy(eventId = "server-end", revision = 1, kind = PushEventKind.TASK_FINISHED, serverConfirmed = true)
        assertEquals(TaskNoticeCoalescing.key(estimate), TaskNoticeCoalescing.key(confirmation))
        assertTrue(TaskNoticeCoalescing.shouldDisplay(confirmation, receipt))
        assertFalse(TaskNoticeCoalescing.shouldDisplay(estimate.copy(revision = 99),
            TaskNoticeReceipt(confirmation.eventId, confirmation.revision, confirmation.finishAtMillis, true)))
    }
}
