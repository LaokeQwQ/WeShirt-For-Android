package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.login.NativeLoginChallenge
import com.laoke.weshirt.platform.login.NativeLoginPreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeLoginPreparationTest {
    @Test fun foregroundWarmupRemovesTheNetworkWaitFromTheNextTap() = runTest {
        var requests = 0
        val preparation = NativeLoginPreparation(backgroundScope, load = {
            requests++; delay(800)
            challenge(testScheduler.currentTime)
        }, valid = { true }, now = { testScheduler.currentTime })
        val warm = launch { preparation.warm("https://service.invalid") }
        runCurrent(); advanceTimeBy(800); runCurrent(); warm.join()
        val tappedAt = testScheduler.currentTime
        val result = preparation.obtain("https://service.invalid")
        assertEquals("https://service.invalid", result.origin)
        assertEquals("No simulated network time is spent after the tap", tappedAt, testScheduler.currentTime)
        assertEquals(1, requests)
    }

    @Test fun aTapDuringWarmupAndARecreatedScreenShareOneChallenge() = runTest {
        var requests = 0
        val response = CompletableDeferred<Unit>()
        val preparation = NativeLoginPreparation(backgroundScope, load = {
            requests++; response.await(); challenge(testScheduler.currentTime)
        }, valid = { true }, now = { testScheduler.currentTime })
        val oldScreen = launch { preparation.warm("https://service.invalid") }
        runCurrent(); oldScreen.cancel(); oldScreen.join()
        val tap = async { preparation.obtain("https://service.invalid") }
        val newScreen = async { preparation.obtain("https://service.invalid") }
        runCurrent(); assertEquals(1, requests)
        response.complete(Unit); runCurrent()
        assertEquals(tap.await(), newScreen.await())
        assertEquals(1, requests)
    }

    @Test fun expiryAndConsumedPrivateStateRequireAFreshChallenge() = runTest {
        var requests = 0
        var acceptedState = ""
        val preparation = NativeLoginPreparation(backgroundScope, load = {
            requests++; acceptedState = "state-$requests"
            challenge(testScheduler.currentTime, acceptedState)
        }, valid = { it.challenge.state == acceptedState }, now = { testScheduler.currentTime })
        assertEquals("state-1", preparation.obtain("https://service.invalid").challenge.state)
        acceptedState = "consumed"
        assertEquals("state-2", preparation.obtain("https://service.invalid").challenge.state)
        advanceTimeBy(170_000)
        assertEquals("state-3", preparation.obtain("https://service.invalid").challenge.state)
        assertEquals(3, requests)
    }

    @Test fun serviceChangesCancelOldPreparationAndCannotReturnItsLateResult() = runTest {
        val oldEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val preparation = NativeLoginPreparation(backgroundScope, load = { origin ->
            if (origin == "https://old.invalid") { oldEntered.complete(Unit); oldRelease.await() }
            challenge(testScheduler.currentTime, origin)
        }, valid = { true }, now = { testScheduler.currentTime })
        val old = async { preparation.obtain("https://old.invalid") }
        runCurrent(); oldEntered.await()
        val newer = preparation.obtain("https://new.invalid")
        oldRelease.complete(Unit)
        try { old.await(); fail("An old origin must not be launched") } catch (_: CancellationException) { }
        assertEquals("https://new.invalid", newer.origin)
        assertEquals("https://new.invalid", newer.challenge.state)
    }

    @Test fun invisibleWarmupFailureIsThrottledButExplicitTapCanRetry() = runTest {
        var requests = 0
        val preparation = NativeLoginPreparation(backgroundScope, load = {
            requests++
            if (requests == 1) error("Temporary network failure")
            challenge(testScheduler.currentTime)
        }, valid = { true }, now = { testScheduler.currentTime })
        preparation.warm("https://service.invalid")
        repeat(10) { preparation.warm("https://service.invalid") }
        assertEquals(1, requests)
        assertNotNull(preparation.obtain("https://service.invalid"))
        assertEquals(2, requests)
    }

    private fun challenge(now: Long, state: String = "fixture-state") = NativeLoginChallenge(
        "fixture-challenge", state, "fixture-client", "https://callback.invalid", now + 180_000,
    )
}
