package com.laoke.weshirt.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A short-lived public-room preview, never credentials, identities or private laundry records. */
class PublicRoomCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(origin: String, building: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest("$origin|$building".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
    fun read(origin: String, building: String): BoardSnapshot? = runCatching {
        if (origin.isBlank() || building.isBlank()) return null
        val source = file(origin, building)
        if (!source.isFile || source.length() > 512_000) return null
        val value = JSONObject(source.readText())
        val age = now() - value.getLong("savedAt")
        if (value.str("origin") != origin || value.str("building") != building || age !in 0..1_800_000) return null
        val machines = value.getJSONArray("machines").objects().map { Machine.parse(it).copy(clockOffsetMs = it.optLong("clockOffsetMs")) }
        if (machines.any { it.id.isBlank() } || machines.map { it.id }.distinct().size != machines.size) return null
        BoardSnapshot(machines,
            value.optLong("updatedAt").takeIf { it > 0 }, "当前显示上次保存的公开状态，正在后台刷新；请以机器现场为准。")
    }.getOrNull()
    fun save(origin: String, building: String, board: BoardSnapshot) { runCatching {
        if (origin.isBlank() || building.isBlank()) return
        val rows = board.machines.map { machine -> JSONObject().put("id", machine.id).put("name", machine.name)
            .put("slot", machine.slot).put("kind", machine.kind).put("state", machine.state)
            .put("finishAt", machine.finishAt?.let { java.time.Instant.ofEpochMilli(it).toString() })
            .put("floorCode", machine.floorCode).put("floorName", machine.floorName).put("originalName", machine.originalName)
            .put("remarkName", machine.remarkName).put("suspectedUncollected", machine.suspectedUncollected)
            .put("estimated", machine.estimated).put("clockOffsetMs", machine.clockOffsetMs) }
        val value = JSONObject().put("origin", origin).put("building", building).put("savedAt", now())
            .put("updatedAt", board.updatedAt).put("machines", JSONArray(rows)).toString()
        if (value.toByteArray().size > 512_000) return
        directory.mkdirs()
        val target = file(origin, building)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(value)
        if (!temporary.renameTo(target)) { target.writeText(value); temporary.delete() }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending(File::lastModified)?.drop(6)?.forEach(File::delete)
    } }
}
