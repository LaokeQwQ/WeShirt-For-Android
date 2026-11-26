package com.laoke.weshirt.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

const val HISTORICAL_BOARD_LOADING_MESSAGE = "正在显示历史数据,正在加载新数据中..."

/** Public room previews contain no credentials, identities or private laundry records. */
class PublicRoomCache(
    private val directory: File,
    private val previousDirectory: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun file(directory: File, origin: String, building: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest("$origin|$building".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
    fun read(origin: String, building: String): BoardSnapshot? = runCatching {
        if (origin.isBlank() || building.isBlank()) return null
        val source = file(directory, origin, building).takeIf(File::isFile)
            ?: previousDirectory?.let { file(it, origin, building) }
            ?: return null
        if (!source.isFile || source.length() > 512_000) return null
        val value = JSONObject(source.readText())
        val age = now() - value.getLong("savedAt")
        if (value.str("origin") != origin || value.str("building") != building || age < 0) return null
        val archived = age > 30 * 60_000L
        val machines = value.getJSONArray("machines").objects().map { row ->
            val machine = Machine.parse(row).copy(clockOffsetMs = row.optLong("clockOffsetMs"))
            if (archived) machine.copy(state = "unknown", finishAt = null, suspectedUncollected = false, estimated = false) else machine
        }
        if (machines.any { it.id.isBlank() } || machines.map { it.id }.distinct().size != machines.size) return null
        BoardSnapshot(machines, value.optLong("updatedAt").takeIf { !archived && it > 0 }, HISTORICAL_BOARD_LOADING_MESSAGE)
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
        val target = file(directory, origin, building)
        val temporary = File.createTempFile("room-", ".tmp", directory)
        try {
            temporary.writeText(value)
            // Android uses a same-filesystem rename to replace the previous complete snapshot.
            temporary.renameTo(target)
        } finally {
            temporary.delete()
        }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending(File::lastModified)?.drop(6)?.forEach(File::delete)
    } }
}
