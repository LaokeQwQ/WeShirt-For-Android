package com.laoke.weshirt.platform

import com.laoke.weshirt.data.ApiException
import com.laoke.weshirt.platform.login.recoverNativeLoginChallenge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeLoginRecoveryTest {
    @Test fun explicitTransientChallengeFailureCreatesOneReplacementAfterBackoff() = runTest {
        val states = mutableListOf<String>()
        val pending = async {
            recoverNativeLoginChallenge("https://login.invalid", { "https://login.invalid" }) {
                val state = "new-state-${states.size}".also(states::add)
                if (states.size == 1) throw ApiException(503, "mobile_unavailable", "evt_cold_store", "暂不可用")
                state
            }
        }
        runCurrent(); assertEquals(1, states.size)
        advanceTimeBy(799); runCurrent(); assertEquals(1, states.size)
        advanceTimeBy(1); runCurrent()
        assertEquals("new-state-1", pending.await())
        assertEquals(listOf("new-state-0", "new-state-1"), states)
    }

    @Test fun secondFailureKeepsTheActualFinalStatusAndTraceWithoutAThirdRequest() = runTest {
        var calls = 0
        try {
            recoverNativeLoginChallenge("https://login.invalid", { "https://login.invalid" }, pause = {}) {
                calls++
                throw ApiException(503, "mobile_busy", "evt_attempt_$calls", "请求繁忙")
            }
            fail("A persistent outage must remain visible")
        } catch (problem: ApiException) { assertEquals(503, problem.status); assertEquals("evt_attempt_2", problem.eventId) }
        assertEquals(2, calls)
    }

    @Test fun configurationQuotaUnknownAndUncertainTransportFailuresAreNotRepeated() = runTest {
        val errors = listOf(
            ApiException(503, "mobile_auth_not_configured", "evt_config", "未配置"),
            ApiException(503, "other_service_error", "evt_other", "其他错误"),
            ApiException(429, "mobile_rate_limited", "evt_quota", "限流"),
            ApiException(0, "network_timeout", "evt_uncertain", "超时"),
        )
        for (expected in errors) {
            var calls = 0
            try {
                recoverNativeLoginChallenge("https://login.invalid", { "https://login.invalid" }, pause = { fail("Non-transient failures must not wait or retry") }) { calls++; throw expected }
                fail("Expected rejection")
            } catch (actual: ApiException) { assertSame(expected, actual) }
            assertEquals(1, calls)
        }
    }

    @Test fun changedOriginOrCancellationDuringBackoffCannotIssueAnotherChallenge() = runTest {
        var origin = "https://old.invalid"
        var calls = 0
        val pending = async {
            recoverNativeLoginChallenge(origin, { origin }) { calls++; throw ApiException(503, "mobile_unavailable", "evt_old", "暂不可用") }
        }
        runCurrent(); origin = "https://new.invalid"; advanceTimeBy(800); runCurrent()
        try { pending.await(); fail("Old service must not be contacted after switching") } catch (_: CancellationException) { }
        assertEquals(1, calls)
        origin = "https://old.invalid"
        val cancelled = async {
            recoverNativeLoginChallenge(origin, { origin }) { calls++; throw ApiException(503, "mobile_unavailable", "evt_cancel", "暂不可用") }
        }
        runCurrent(); cancelled.cancel(); advanceTimeBy(800); runCurrent()
        assertEquals(2, calls)
    }
}
