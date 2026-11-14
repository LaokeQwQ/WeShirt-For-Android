package com.laoke.weshirt.platform

import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Building
import com.laoke.weshirt.data.LaundryTask
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.data.Profile
import com.laoke.weshirt.platform.widget.LaundryWidgetState
import com.laoke.weshirt.platform.widget.WidgetTaskState
import com.laoke.weshirt.platform.widget.decodeWidgetSnapshot
import com.laoke.weshirt.platform.widget.encodeWidgetSnapshot
import com.laoke.weshirt.platform.widget.laundryWidgetScope
import com.laoke.weshirt.platform.widget.projectLaundryWidget
import com.laoke.weshirt.platform.widget.acceptWidgetSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LaundryWidgetProjectionTest {
    private val now = 1_800_000_000_000L
    private val offset = 7 * 60_000L
    private val task = LaundryTask("task-1", "machine-1", "原名称", "dryer", now + offset + 600_000,
        false, "private-task-note", true, true, false,
        startAt = now + offset - 600_000, createdAt = now + offset - 700_000, clockOffsetMs = offset)
    private val machine = Machine("machine-1", "备注机器", "D2", "dryer", "occupied", task.endAt,
        "private-machine-note", true, true, "", occupantId = "private-user", clockOffsetMs = offset)
    private fun state() = AppState(endpoint = "https://widget.invalid", building = "test", buildings = listOf(Building("test", "测试楼")),
        profile = Profile("test-user", "private-nick", "private-avatar", "test", 1, "user", mobile = "private-mobile", notifyOn = false),
        access = JSONObject("""{"permissions":{"tasks":{"view":true}}}"""), machines = listOf(machine), tasks = listOf(task),
        updatedAt = now + offset - 2_000, tasksUpdatedAt = now - 5_000)

    @Test fun cachedIdentityDoesNotGrantWidgetAccessAndNotificationConsentDoesNotGateIt() {
        assertNull(projectLaundryWidget(state().copy(authReady = false, sessionRestoring = true), now))
        assertNull(projectLaundryWidget(state().copy(profile = null), now))
        assertNull(projectLaundryWidget(state().copy(building = ""), now))
        // This account disabled notifications; its own verified task still belongs in the widget.
        assertEquals("task-1", projectLaundryWidget(state(), now)?.taskId)
    }

    @Test fun serverClockIsMappedOnceForDeadlineStartAndFreshnessAndMachineNumberIsKept() {
        val snapshot = projectLaundryWidget(state(), now)!!
        assertEquals(now + 600_000, snapshot.taskEstimatedFinishAtMillis)
        assertEquals(now - 600_000, snapshot.taskStartedAtMillis)
        assertEquals(now - 5_000, snapshot.updatedAtMillis)
        assertEquals("D2 号烘干机 · 备注机器", snapshot.taskMachineName)
        assertEquals("dryer", snapshot.taskKind)
        assertEquals(10, LaundryWidgetState.remainingMinutes(snapshot, now))
        assertEquals(50, LaundryWidgetState.progressPercent(snapshot, now))
        assertTrue(LaundryWidgetState.isRunning(snapshot, now))
    }

    @Test fun deniedTaskPermissionRemovesAllTaskFieldsEvenIfOldTaskListRemainsInMemory() {
        val denied = state().copy(access = JSONObject("""{"permissions":{"tasks":{"view":false}}}"""))
        val snapshot = projectLaundryWidget(denied, now)!!
        assertNull(snapshot.taskId); assertNull(snapshot.taskMachineName)
        assertNull(snapshot.taskEstimatedFinishAtMillis); assertNull(snapshot.taskStartedAtMillis)
        assertNull(snapshot.taskKind)
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(snapshot, now))
        assertNull(projectLaundryWidget(denied.copy(authReady = false), now))
        assertNull(projectLaundryWidget(state().copy(access = JSONObject()), now))
        assertEquals("task-1", projectLaundryWidget(state().copy(access = JSONObject("""{"permissions":{}}""")), now)?.taskId)
        assertEquals("task-1", projectLaundryWidget(state().copy(access = JSONObject("""{"permissions":{"tasks":{}}}""")), now)?.taskId)
    }

    @Test fun newBoardDoesNotMakeOldFailedTaskRequestLookCurrent() {
        val old = projectLaundryWidget(state().copy(tasksUpdatedAt = now - 700_000), now)!!
        assertEquals(now - 700_000, old.updatedAtMillis)
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(old, now))
        val failed = projectLaundryWidget(state().copy(tasksRefreshFailed = true), now)!!
        assertEquals(WidgetTaskState.UNVERIFIED, LaundryWidgetState.taskState(failed, now))
        assertNull(LaundryWidgetState.remainingMinutes(failed, now)); assertNull(LaundryWidgetState.progressPercent(failed, now))
        assertFalse(LaundryWidgetState.isRunning(failed, now))
        assertTrue(projectLaundryWidget(state().copy(tasksUpdatedAt = null), now)!!.taskUnverified)
        assertTrue(projectLaundryWidget(state().copy(boardFromCache = true), now)!!.taskUnverified)
    }

    @Test fun realStartIsRequiredForPercentageAndZeroDoesNotMeanFinished() {
        val noStart = projectLaundryWidget(state().copy(tasks = listOf(task.copy(startAt = null))), now)!!
        assertNull(LaundryWidgetState.progressPercent(noStart, now))
        val invalidStart = noStart.copy(taskStartedAtMillis = now + 1)
        assertNull(LaundryWidgetState.progressPercent(invalidStart, now))
        val zero = noStart.copy(taskEstimatedFinishAtMillis = now)
        assertEquals(0, LaundryWidgetState.remainingMinutes(zero, now))
        assertEquals(WidgetTaskState.AWAITING_CONFIRMATION, LaundryWidgetState.taskState(zero, now))
        assertFalse(LaundryWidgetState.isRunning(zero, now))
        val confirmed = projectLaundryWidget(state().copy(tasks = listOf(task.copy(completionConfirmed = true))), now)!!
        assertEquals(WidgetTaskState.READY_FOR_PICKUP, LaundryWidgetState.taskState(confirmed, now))
        assertFalse(LaundryWidgetState.isRunning(confirmed, now))
    }

    @Test fun onlyCurrentRecordsAreSelectedAndTheirLifecycleUsesServerClock() {
        val done = task.copy(id = "done", done = true)
        val archived = task.copy(id = "archived", archived = true)
        val expired = task.copy(id = "expired", expiresAt = now + offset - 1)
        val aged = task.copy(id = "aged", createdAt = now + offset - 72 * 3_600_000L)
        val past = task.copy(id = "past", endAt = now + offset - 60_000)
        val next = task.copy(id = "next", endAt = now + offset + 120_000)
        val pending = task.copy(id = "confirmed", completionConfirmed = true)
        assertEquals("next", projectLaundryWidget(state().copy(tasks = listOf(done, archived, expired, aged, past, task, next, pending)), now)!!.taskId)
        assertNull(projectLaundryWidget(state().copy(tasks = listOf(done, archived, expired, aged)), now)!!.taskId)
    }

    @Test fun persistedDisplayProjectionExcludesEveryPrivateSourceField() {
        val snapshot = projectLaundryWidget(state(), now)!!
        val encoded = encodeWidgetSnapshot(snapshot)
        val raw = encoded.toString()
        listOf("private-task-note", "private-machine-note", "private-user", "private-nick", "private-avatar", "private-mobile", "test-user", "https://widget.invalid")
            .forEach { assertFalse("The display snapshot must not contain $it", raw.contains(it)) }
        assertEquals(snapshot, decodeWidgetSnapshot(encoded))
        val legacy = decodeWidgetSnapshot(JSONObject("""{"building":"Test","idle":9,"updated":1800000000000,"machine":"Washer 1","finish":1800000060000}"""))
        assertNull(legacy.taskStartedAtMillis); assertNull(legacy.taskId); assertNull(legacy.taskKind)
        assertFalse(legacy.taskUnverified); assertFalse(legacy.taskCompletionConfirmed)
    }

    @Test fun sessionScopeSurvivesSecretRotationButRejectsOtherLoginOriginAndUser() {
        fun credentials(token: String = "ma1.session-one.secret-one", user: String = "user-one", origin: String = "https://widget.invalid") =
            JSONObject().put("origin", origin).put("accessToken", token).put("user", JSONObject().put("id", user))
        val first = laundryWidgetScope(credentials())!!
        assertEquals(first, laundryWidgetScope(credentials("ma1.session-one.secret-two")))
        assertNotEquals(first, laundryWidgetScope(credentials("ma1.session-two.secret-two")))
        assertNotEquals(first, laundryWidgetScope(credentials(user = "user-two")))
        assertNotEquals(first, laundryWidgetScope(credentials(origin = "https://another.invalid")))
        assertTrue(first.key.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first.key, first.toString())
        assertFalse(first.key.contains("secret")); assertFalse(first.toString().contains("user-one"))
        assertNull(laundryWidgetScope(credentials(user = "")))
        assertNull(laundryWidgetScope(credentials(token = "")))
    }

    @Test fun lateWorkerCannotReplaceANewerForegroundSnapshotButEqualTimeWarningsCanPublish() {
        val current = projectLaundryWidget(state(), now)!!
        assertTrue(acceptWidgetSnapshot(null, current))
        assertFalse(acceptWidgetSnapshot(current, current.copy(updatedAtMillis = current.updatedAtMillis - 1,
            taskEstimatedFinishAtMillis = now + 300_000)))
        assertTrue(acceptWidgetSnapshot(current, current.copy(updatedAtMillis = current.updatedAtMillis + 1)))
        assertTrue(acceptWidgetSnapshot(current, current.copy(taskUnverified = true)))
    }
}
