package com.laoke.weshirt.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistantPreloaderTest {
    private val account = AssistantScope("https://assistant.invalid", "user-a", 1)

    @Test fun slowStatusDoesNotHoldHistoryAndConcurrentOpenJoinsEachPanel() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = mutableMapOf<String, Int>()
        val seen = mutableMapOf<String, AssistantPanel>()
        val loader = AssistantPreloader({ path ->
            calls[path] = (calls[path] ?: 0) + 1
            if (path.endsWith("/status")) release.await()
            JSONObject().put("value", path)
        }, { testScheduler.currentTime })
        val first = launch { loader.load(account, setOf("status", "history"), current = { true }) { key, panel -> seen[key] = panel } }
        runCurrent()
        assertNotNull(seen["history"]?.data); assertTrue(seen["status"]!!.loading)
        val second = launch { loader.load(account, setOf("status", "history"), current = { true }) { key, panel -> seen[key] = panel } }
        runCurrent(); assertEquals(1, calls["/api/assistant/status"])
        release.complete(Unit); first.join(); second.join()
        assertEquals(1, calls["/api/assistant/history"]); assertFalse(seen["status"]!!.loading)
    }

    @Test fun ttlAndFailuresAreIndependentAndCallerCannotMutateCachedJson() = runTest {
        var now = 1L
        var fail = false
        val calls = mutableMapOf<String, Int>()
        val seen = mutableMapOf<String, AssistantPanel>()
        val loader = AssistantPreloader({ path ->
            calls[path] = (calls[path] ?: 0) + 1
            if (path.endsWith("/status") && fail) throw ApiException(503, "unavailable", "fixture-event", "暂不可用")
            JSONObject().put("value", "saved")
        }, { now })
        suspend fun read(force: Boolean = false) = loader.load(account, setOf("status", "memory"), force, { true }) { key, panel -> seen[key] = panel }
        read(); seen["memory"]!!.data!!.put("value", "UI edit")
        now += 30_001; fail = true; read()
        assertEquals(2, calls["/api/assistant/status"]); assertEquals(1, calls["/api/assistant/memory"])
        assertEquals("saved", seen["memory"]!!.data!!.str("value"))
        assertEquals("saved", seen["status"]!!.data!!.str("value")); assertTrue(seen["status"]!!.error!!.contains("503"))
        read(); assertEquals(2, calls["/api/assistant/status"])
        fail = false; read(true); assertNull(seen["status"]!!.error); assertEquals(3, calls["/api/assistant/status"])
    }

    @Test fun invalidationDuringReadDiscardsOldResponseAndWaitingRefreshFetchesSavedValue() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val seen = mutableListOf<String>()
        val loader = AssistantPreloader({
            calls++
            if (calls == 1) { release.await(); JSONObject().put("note", "old") } else JSONObject().put("note", "saved")
        }, { testScheduler.currentTime })
        val first = launch { loader.load(account, setOf("memory"), current = { true }) { _, panel -> panel.data?.let { seen += it.str("note") } } }
        runCurrent()
        loader.invalidate(account, setOf("memory"))
        val second = launch { loader.load(account, setOf("memory"), true, { true }) { _, panel -> panel.data?.let { seen += it.str("note") } } }
        runCurrent(); release.complete(Unit); first.join(); second.join()
        assertEquals(2, calls); assertEquals(listOf("saved"), seen)
    }

    @Test fun changedLoginCannotPublishAndDoesNotWaitBehindOldAccountRequest() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var current = account
        val seen = mutableListOf<String>()
        val loader = AssistantPreloader({
            calls++
            if (calls == 1) { release.await(); JSONObject().put("account", "old") } else JSONObject().put("account", "new")
        }, { testScheduler.currentTime })
        val first = launch { loader.load(account, setOf("memory"), current = { current == account }) { _, panel -> panel.data?.let { seen += it.str("account") } } }
        runCurrent(); loader.clear(); current = account.copy(userId = "user-b", generation = 2)
        loader.load(current, setOf("memory"), current = { true }) { _, panel -> panel.data?.let { seen += it.str("account") } }
        assertEquals(listOf("new"), seen)
        release.complete(Unit); first.join(); assertEquals(listOf("new"), seen)
    }

    @Test fun confirmedWriteSurvivesOldGetAndFailedReconciliationWhenReopening() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val seen = mutableListOf<AssistantPanel>()
        val loader = AssistantPreloader({
            calls++
            if (calls == 1) { release.await(); JSONObject().put("note", "old") }
            else throw ApiException(503, "unavailable", "fixture-event", "暂不可用")
        }, { testScheduler.currentTime })
        val first = launch { loader.load(account, setOf("memory"), current = { true }) { _, panel -> seen += panel } }
        runCurrent()
        val saved = JSONObject().put("note", "confirmed")
        loader.accept(account, "memory", saved); saved.put("note", "late UI edit")
        loader.invalidate(account, setOf("memory"))
        val reconcile = launch { loader.load(account, setOf("memory"), true, { true }) { _, panel -> seen += panel } }
        runCurrent(); release.complete(Unit); first.join(); reconcile.join()
        assertFalse(seen.any { it.data?.str("note") == "old" })
        loader.load(account, setOf("memory"), current = { true }) { _, panel -> seen += panel }
        assertEquals("confirmed", seen.last().data!!.str("note"))
        assertTrue(seen.last().error!!.contains("503")); assertEquals(2, calls)
    }
}
