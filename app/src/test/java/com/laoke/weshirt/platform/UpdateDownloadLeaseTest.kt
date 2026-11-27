package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.update.UpdateDownloadLease
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UpdateDownloadLeaseTest {
    @Test fun destroyedServiceCannotReleaseSlotWhileUninterruptibleVerificationStillOwnsTheFile() {
        val lease = UpdateDownloadLease()
        assertTrue(lease.acquire("old"))
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val verification = executor.submit {
                entered.countDown()
                assertTrue(finish.await(3, TimeUnit.SECONDS))
                // The worker completion callback is later than every delete()/notification/finally.
                lease.workerFinished("old")
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            lease.serviceDestroyed("old") // onTimeout/stopService does not wait for package parsing.
            assertFalse("New work must not touch the same APK while old verification unwinds", lease.acquire("new"))
            finish.countDown()
            verification.get(2, TimeUnit.SECONDS)
            assertTrue(lease.acquire("new"))
            lease.workerFinished("old")
            lease.serviceDestroyed("old")
            assertFalse("A delayed callback from the old Service cannot free its successor", lease.acquire("third"))
        } finally { finish.countDown(); executor.shutdownNow() }
    }

    @Test fun completedWorkerWaitsForServiceDestructionBeforeASecondStartCanReuseTheService() {
        val lease = UpdateDownloadLease()
        assertTrue(lease.acquire("one"))
        lease.workerFinished("one")
        assertFalse("stopSelf is asynchronous; a completed coroutine alone is insufficient", lease.acquire("two"))
        lease.serviceDestroyed("one")
        assertTrue(lease.acquire("two"))
        lease.serviceDestroyed("two")
        assertFalse(lease.acquire("three"))
        lease.workerFinished("two")
        assertTrue(lease.acquire("three"))
    }

    @Test fun rejectedStartCanReleaseItsOwnLeaseButCannotReleaseAnotherRequest() {
        val lease = UpdateDownloadLease()
        assertTrue(lease.acquire("one"))
        lease.startRejected("unrelated")
        assertFalse(lease.acquire("two"))
        lease.startRejected("one")
        assertTrue(lease.acquire("two"))
    }
}
