package com.laoke.weshirt.platform.widget

import com.laoke.weshirt.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LaundryWidgetRefreshTest {
    private val now = 1_900_000_000_000L
    private val at get() = Instant.ofEpochMilli(now).toString()
    private fun profile(id: String = "one", building: String = "guiyuan") = JSONObject().put("user",
        JSONObject().put("id", id).put("dormBuilding", building))
    private fun access(view: Boolean = true) = JSONObject().put("permissions", JSONObject().put("tasks", JSONObject().put("view", view)))
    private fun site(enabled: Boolean = true) = JSONObject().put("buildings", JSONArray().put(JSONObject().put("code", "guiyuan").put("name", "测试楼").put("enabled", enabled)))
    private fun board(building: String = "guiyuan") = JSONObject().put("board", JSONObject().put("dormBuilding", building).put("updatedAt", at)
        .put("floors", JSONArray().put(JSONObject().put("code", "1F").put("machines", JSONArray().put(JSONObject().put("id", "machine-one").put("name", "1号").put("state", "occupied"))))))
    private fun response(path: String) = when (path) {
        "/api/auth/me" -> profile()
        "/api/user/permissions" -> access()
        "/api/site" -> site()
        "/api/machines?dorm=guiyuan" -> board()
        "/api/tasks" -> JSONObject().put("serverNow", at).put("tasks", JSONArray())
        else -> throw AssertionError("Unexpected endpoint $path")
    }

    @Test fun refreshVerifiesIdentityAndPermissionsBeforeOnlyOwnTasksAndBoundRoomReads() = runTest {
        val requests = mutableListOf<Pair<String, Boolean>>()
        val state = readVerifiedWidgetState("one", { path, auth -> requests += path to auth; response(path) }, { true }, { now })
        assertEquals(listOf("/api/auth/me", "/api/user/permissions", "/api/site"), requests.take(3).map { it.first })
        assertEquals(setOf("/api/auth/me", "/api/user/permissions", "/api/site", "/api/machines?dorm=guiyuan", "/api/tasks"), requests.map { it.first }.toSet())
        assertFalse(requests.single { it.first.startsWith("/api/machines") }.second)
        assertTrue(requests.single { it.first == "/api/tasks" }.second)
        assertEquals("one", state.profile?.id); assertEquals("guiyuan", state.building)
        assertTrue(state.authReady); assertEquals("machine-one", state.machines.single().id)
    }

    @Test fun deniedTaskViewNeverReadsOrCachesTaskEndpointEvenForAnAdministrator() = runTest {
        val paths = mutableListOf<String>()
        val state = readVerifiedWidgetState("one", { path, _ -> paths += path; if (path == "/api/user/permissions") access(false) else response(path) }, { true }, { now })
        assertFalse(paths.contains("/api/tasks")); assertTrue(state.tasks.isEmpty())
    }

    @Test fun missingPermissionsAndDisabledBuildingStopBeforePrivateOrOtherRoomReads() = runTest {
        for (invalidPermissions in listOf(true, false)) {
            val paths = mutableListOf<String>()
            try {
                readVerifiedWidgetState("one", { path, _ ->
                    paths += path
                    when {
                        invalidPermissions && path == "/api/user/permissions" -> JSONObject()
                        !invalidPermissions && path == "/api/site" -> site(false)
                        else -> response(path)
                    }
                }, { true }, { now })
                fail("Unverified permissions/building must reject refresh")
            } catch (expected: ApiException) {
                assertTrue(expected.status == 502 || expected.status == 409)
            }
            assertFalse(paths.any { it.startsWith("/api/machines") || it == "/api/tasks" })
        }
    }

    @Test fun accountMismatchStopsAtMeAndLateAccountSwitchCancelsBeforePublication() = runTest {
        val paths = mutableListOf<String>()
        try {
            readVerifiedWidgetState("other", { path, _ -> paths += path; response(path) }, { true }, { now })
            fail("Mismatching identity must cancel")
        } catch (_: CancellationException) { }
        assertEquals(listOf("/api/auth/me"), paths)
        var current = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = async {
            readVerifiedWidgetState("one", { path, _ -> if (path == "/api/auth/me") { entered.complete(Unit); release.await() }; response(path) }, { current }, { now })
        }
        entered.await(); current = false; release.complete(Unit)
        try { pending.await(); fail("Late identity response must cancel") } catch (_: CancellationException) { }
    }

    @Test fun providerFailuresKeepTheirStatusAndTraceAndAreNotSilentlyRetried() = runTest {
        var attempts = 0
        val failure = ApiException(503, "temporarily_unavailable", "evt_fixture123", "读取失败")
        try {
            readVerifiedWidgetState("one", { _, _ -> attempts++; throw failure }, { true }, { now })
            fail("Provider failure must propagate")
        } catch (expected: ApiException) { assertSame(failure, expected); assertTrue(expected.message!!.contains("503")); assertTrue(expected.message!!.contains("evt_fixture123")) }
        assertEquals(1, attempts)
    }

    @Test fun taskRowsExplicitlyBelongingToAnotherAccountAreExcluded() = runTest {
        val tasks = JSONObject().put("tasks", JSONArray().put(JSONObject().put("id", "mine").put("userId", "one"))
            .put(JSONObject().put("id", "foreign").put("userId", "two")))
        val state = readVerifiedWidgetState("one", { path, _ -> if (path == "/api/tasks") tasks else response(path) }, { true }, { now })
        assertEquals(listOf("mine"), state.tasks.map { it.id })
    }

    @Test fun minuteRedrawChainIsBoundedAndCannotDeclareHardwareCompletion() {
        val snapshot = LaundryWidgetSnapshot("测试楼", 2, 1, 0, now, "1号", now + 120_000)
        assertEquals(60_000L, WidgetRefreshPolicy.tickDelay(snapshot, now))
        assertEquals(1L, WidgetRefreshPolicy.tickDelay(snapshot, now + 599_999))
        assertNull(WidgetRefreshPolicy.tickDelay(snapshot, now + 600_000))
        assertNull(WidgetRefreshPolicy.tickDelay(snapshot.copy(taskUnverified = true), now))
        assertNull(WidgetRefreshPolicy.tickDelay(snapshot.copy(taskCompletionConfirmed = true), now))
        assertNull(WidgetRefreshPolicy.tickDelay(snapshot.copy(taskMachineName = null), now))
        assertEquals(60_000L, WidgetRefreshPolicy.tickDelay(snapshot.copy(taskEstimatedFinishAtMillis = now - 1), now))
        assertEquals(WidgetTaskState.AWAITING_CONFIRMATION, LaundryWidgetState.taskState(snapshot.copy(taskEstimatedFinishAtMillis = now - 1), now))
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(snapshot, now + 600_001))
        assertFalse(WidgetRefreshPolicy.mayRefresh(now + 29_999, now))
        assertTrue(WidgetRefreshPolicy.mayRefresh(now + 30_000, now))
    }

    @Test fun persistedRefreshErrorKeepsStatusAndTraceWithoutProviderTextOrSecrets() {
        val error=ApiException(503,"unavailable","evt_012345678901234567890123","private response body ".repeat(30))
        val label=widgetRefreshError(error)
        assertEquals("刷新失败 · HTTP 503 · 事件 evt_012345678901234567890123",label)
        assertTrue(label.length<100);assertFalse(label.contains("private"))
        val unsafe=widgetRefreshError(ApiException(503,"unavailable","evt_unsafe\nprivate body","ignored"))
        assertTrue(unsafe.contains("HTTP 503"));assertTrue(unsafe.contains("client_"));assertFalse(unsafe.contains("unsafe"))
    }
}
