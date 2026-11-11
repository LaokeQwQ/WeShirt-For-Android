package com.laoke.weshirt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskLifecycleTest {
    private val created = 1_900_000_000_000L
    private fun task(id: String, ageHours: Long = 0, done: Boolean = false) = LaundryTask(id, "machine", "机器", "washer", created + 100_000, done, "", false, false, false, created + ageHours * 3_600_000, created)
    @Test fun archivesAt72HoursAndExpiresExactlyAt336HoursRegardlessOfTakeStateOrStartTime() {
        val before = laundryRecords(listOf(task("new"), task("taken", done = true)), created + 72 * 3_600_000 - 1)
        assertEquals(2, before.current.size); assertTrue(before.history.isEmpty())
        val at = laundryRecords(listOf(task("new", 400), task("taken", done = true)), created + 72 * 3_600_000)
        assertTrue(at.current.isEmpty()); assertEquals(2, at.history.size)
        assertEquals(1, laundryRecords(listOf(task("new")), created + 336 * 3_600_000 - 1).history.size)
        assertTrue(laundryRecords(listOf(task("new")), created + 336 * 3_600_000).history.isEmpty())
    }
    @Test fun explicitServerArchiveWinsAndLegacyMissingDatesDoNotDeleteRecords() {
        val parsed = LaundryTask.parse(JSONObject().put("id", "remote").put("archived", true).put("createdAt", "2030-03-17T17:46:40Z").put("expiresAt", "2030-03-31T17:46:40Z"))
        assertTrue(parsed.archived); assertNotNull(parsed.createdAt); assertNotNull(parsed.expiresAt)
        assertEquals(1, laundryRecords(listOf(parsed), 0).history.size)
        val legacy = task("old").copy(createdAt = null)
        assertFalse(legacy.isExpired(Long.MAX_VALUE)); assertFalse(legacy.isArchived(Long.MAX_VALUE))
    }
}
