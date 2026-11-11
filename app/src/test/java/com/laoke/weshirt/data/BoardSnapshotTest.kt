package com.laoke.weshirt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoardSnapshotTest {
    @Test fun onlyRequestedBuildingCanBePublishedAndPhysicalTimestampIsPreserved() {
        val response = JSONObject("""{"board":{"dormBuilding":"one","updatedAt":"2030-01-01T00:00:00Z","floors":[{"code":"ground","name":"一楼","machines":[{"id":"washer","state":"occupied"}]}]}}""")
        val parsed = parseBoardSnapshot(response, "one")
        assertEquals(epoch("2030-01-01T00:00:00Z"), parsed.updatedAt)
        assertEquals("ground", parsed.machines.single().floorCode)
        assertEquals("一楼", parsed.machines.single().floorName)
        val failure = runCatching { parseBoardSnapshot(response, "another") }.exceptionOrNull() as ApiException
        assertEquals(502, failure.status); assertEquals("invalid_board", failure.code)
    }
    @Test fun failedSupplementIsRecoverableButAccountCancellationIsNeverSwallowed() = runBlocking {
        val optional = optionalRefresh<JSONObject> { throw ApiException(503, "unavailable", "evt_queue", "队列未刷新") }
        assertTrue(optional.isFailure)
        assertEquals("evt_queue", (optional.exceptionOrNull() as ApiException).eventId)
        try { optionalRefresh<Unit> { throw CancellationException("Account changed") }; fail("Account cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun olderReplicaCannotRewindTheSameRoomButChangingRoomAlwaysReplacesIt() {
        val latestMachine = Machine("latest", "最新机器", "1", "washer", "occupied", null, "", false, false, "")
        val incomingMachine = latestMachine.copy(id = "older", state = "idle")
        val previous = listOf(latestMachine)
        val incoming = BoardSnapshot(listOf(incomingMachine), 10L)
        val protected = preferLatestBoard(incoming, "one", "one", previous, 20L)
        assertEquals(previous, protected.machines); assertEquals(20L, protected.updatedAt)
        assertNotNull(protected.warning)
        assertEquals(incoming, preferLatestBoard(incoming, "two", "one", previous, 20L))
        val equalTime = incoming.copy(updatedAt = 20L)
        assertEquals(equalTime, preferLatestBoard(equalTime, "one", "one", previous, 20L))
        assertEquals(previous, preferLatestBoard(incoming.copy(updatedAt = null), "one", "one", previous, 20L).machines)
    }

    @Test fun failedSyncCarriesPersistentWarningAndServerEventIdEvenIfTheRequestSucceeded() {
        val response = JSONObject("""{"board":{"dormBuilding":"one","floors":[]},"freshness":{"syncFailed":true,"eventId":"evt_sync_failure","stale":true}}""")
        val warning = parseBoardSnapshot(response, "one").warning.orEmpty()
        assertTrue(warning.contains("上次成功")); assertTrue(warning.contains("503")); assertTrue(warning.contains("evt_sync_failure"))
        assertFalse(warning.contains("最新"))
        response.getJSONObject("freshness").put("syncFailed", false).put("stale", false)
        assertNull(parseBoardSnapshot(response, "one").warning)
    }
}
