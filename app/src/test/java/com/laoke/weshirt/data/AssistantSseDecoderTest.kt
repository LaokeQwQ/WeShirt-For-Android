package com.laoke.weshirt.data

import org.junit.Assert.*
import org.junit.Test

class AssistantSseDecoderTest {
    @Test fun heartbeatAndMultilineDataDoNotDelayOrCorruptUnicodeDeltas() {
        val decoder = AssistantSseDecoder("evt_fixture")
        assertNull(decoder.line("\uFEFF: heartbeat"))
        assertNull(decoder.line(""))
        decoder.line("event: delta")
        decoder.line("data: {\"text\":")
        decoder.line("data: \"衣服洗好了\"}")
        assertEquals(AssistantStreamEvent.Delta("衣服洗好了"), decoder.line(""))
        decoder.line("event: done")
        decoder.line("data: {\"reply\":\"衣服洗好了\",\"messages\":[]}")
        assertTrue(decoder.line("") is AssistantStreamEvent.Done)
        decoder.end()
    }

    @Test fun terminalErrorsKeepStatusAndTraceAndEndWithoutDoneIsNotSuccess() {
        val decoder = AssistantSseDecoder("evt_fallback")
        decoder.line("event: error")
        decoder.line("data: {\"error\":{\"status\":429,\"code\":\"rate_limit\",\"message\":\"请稍后重试\",\"eventId\":\"evt_server\"}}")
        try { decoder.line(""); fail("Expected the server stream error") }
        catch (problem: ApiException) { assertEquals(429, problem.status); assertEquals("evt_server", problem.eventId) }
        try { AssistantSseDecoder("evt_incomplete").end(); fail("EOF cannot confirm a saved answer") }
        catch (problem: ApiException) { assertEquals("assistant_stream_interrupted", problem.code) }
    }

    @Test fun oversizedOrMalformedDeltasAreRejectedBeforePublication() {
        val decoder = AssistantSseDecoder("evt_bounds")
        decoder.line("event: delta"); decoder.line("data: {\"text\":\"" + "衣".repeat(6001) + "\"}")
        try { decoder.line(""); fail("Answer bounds must apply before display") }
        catch (problem: ApiException) { assertEquals("assistant_stream_invalid", problem.code) }
        val malformed = AssistantSseDecoder("evt_format")
        malformed.line("event: delta"); malformed.line("data: [1,2]")
        try { malformed.line(""); fail("An arbitrary upstream payload is not a delta") }
        catch (problem: ApiException) { assertEquals(502, problem.status) }
    }

    @Test fun toolRoundResetStartsANewVisibleAnswerWithoutResettingTheTotalStreamBound() {
        val decoder = AssistantSseDecoder("evt_reset")
        decoder.line("event: delta"); decoder.line("data: {\"text\":\"" + "a".repeat(6000) + "\"}")
        assertTrue(decoder.line("") is AssistantStreamEvent.Delta)
        decoder.line("event: reset"); decoder.line("data: {}")
        assertEquals(AssistantStreamEvent.Reset, decoder.line(""))
        decoder.line("event: delta"); decoder.line("data: {\"text\":\"新的回答\"}")
        assertEquals(AssistantStreamEvent.Delta("新的回答"), decoder.line(""))
    }
}
