package com.laoke.weshirt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PublicRoomCacheTest {
    @Test fun offlineRoomKeepsOldLayoutWithoutPresentingExpiredStatusOrPersonalDetails() {
        val directory = Files.createTempDirectory("public-room").toFile()
        var now = 100L
        val cache = PublicRoomCache(directory) { now }
        val machine = Machine.parse(JSONObject().put("id", "m1").put("name", "1号").put("slot", "1").put("state", "occupied")
            .put("note", "private fixture note").put("occupancy", JSONObject().put("userId", "private fixture owner")))
        try {
            cache.save("https://room.invalid", "building-a", BoardSnapshot(listOf(machine), 50L))
            val preview = cache.read("https://room.invalid", "building-a")!!
            assertEquals("m1", preview.machines.single().id); assertEquals(50L, preview.updatedAt)
            assertTrue(preview.warning!!.contains("上次")); assertEquals("", preview.machines.single().occupantId); assertEquals("", preview.machines.single().note)
            assertFalse(directory.listFiles()!!.single().readText().contains("private fixture"))
            assertNull(cache.read("https://other.invalid", "building-a")); assertNull(cache.read("https://room.invalid", "building-b"))
            now += 86_400_000
            val nextDay = cache.read("https://room.invalid", "building-a")!!
            assertEquals("m1", nextDay.machines.single().id)
            assertEquals("unknown", nextDay.machines.single().state)
            assertNull(nextDay.machines.single().finishAt)
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptOversizedOrFutureCacheDoesNotPreventStartup() {
        val directory = Files.createTempDirectory("public-room-invalid").toFile()
        var now = 100L
        val cache = PublicRoomCache(directory) { now }
        try {
            cache.save("https://room.invalid", "building", BoardSnapshot(emptyList(), null))
            now = 1; assertNull(cache.read("https://room.invalid", "building"))
            now = 100; val file = directory.listFiles()!!.single()
            file.writeText("invalid"); assertNull(cache.read("https://room.invalid", "building"))
            file.writeText("x".repeat(512_001)); assertNull(cache.read("https://room.invalid", "building"))
        } finally { directory.deleteRecursively() }
    }
}
