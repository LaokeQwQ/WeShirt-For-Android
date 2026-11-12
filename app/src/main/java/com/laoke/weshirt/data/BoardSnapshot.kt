package com.laoke.weshirt.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.UUID

data class BoardSnapshot(val machines: List<Machine>, val updatedAt: Long?, val warning: String? = null)

/** KV replicas may return an older successful snapshot. Never rewind the current room. */
fun preferLatestBoard(incoming: BoardSnapshot, building: String, previousBuilding: String, machines: List<Machine>, updatedAt: Long?): BoardSnapshot =
    if (building == previousBuilding && updatedAt != null && (incoming.updatedAt == null || incoming.updatedAt < updatedAt))
        incoming.copy(machines = machines, updatedAt = updatedAt, warning = incoming.warning ?: "暂时收到较旧快照，已保留本楼较新的状态；稍后自动刷新。")
    else incoming

fun parseBoardSnapshot(response: JSONObject, building: String): BoardSnapshot {
    val board = response.optJSONObject("board")
    if (board == null || board.str("dormBuilding") != building || board.optJSONArray("floors") == null) throw ApiException(502, "invalid_board", "client_${UUID.randomUUID()}", "楼栋数据不匹配，请重试")
    val clockOffset = epoch(response.optJSONObject("freshness")?.str("serverNow").orEmpty())?.minus(System.currentTimeMillis()) ?: 0L
    val machines = board.getJSONArray("floors").objects().flatMap { floor ->
        floor.optJSONArray("machines")?.objects().orEmpty().map { value ->
            Machine.parse(value).let { machine -> machine.copy(floorCode = machine.floorCode.ifBlank { floor.str("code") }, floorName = machine.floorName.ifBlank { floor.str("name", floor.str("code")) }, clockOffsetMs = clockOffset) }
        }
    }
    if (machines.any { it.id.isBlank() } || machines.map { it.id }.distinct().size != machines.size) throw ApiException(502, "invalid_board", "client_${UUID.randomUUID()}", "机器数据异常，请重试")
    val freshness = response.optJSONObject("freshness")
    val warning = when {
        freshness?.optBoolean("syncFailed") == true -> "最近一次设备数据同步未成功，当前显示上次成功的数据。\n状态码 503 · 事件 ${freshness.str("eventId").ifBlank { "client_${UUID.randomUUID()}" }}"
        freshness?.optBoolean("stale") == true -> "设备数据更新有延迟，请以机器现场为准。"
        else -> null
    }
    return BoardSnapshot(machines, epoch(board.str("updatedAt")), warning)
}

/** Optional service failures must not cancel a successful board refresh. Account changes still cancel it. */
suspend fun <T> optionalRefresh(block: suspend () -> T): Result<T> = try { Result.success(block()) }
catch (cancel: CancellationException) { throw cancel }
catch (problem: Exception) { Result.failure(problem) }
