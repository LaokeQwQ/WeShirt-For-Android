package com.laoke.weshirt.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class InspectionIssue(val machineId: String, val reasons: Set<String> = emptySet(), val note: String = "")

object InspectionRound {
    val reasons = linkedMapOf("offline" to "洗衣机离线", "damaged" to "机器损坏", "power_off" to "断电", "abnormal_occupation" to "异常占用", "other" to "其他异常")

    fun body(building: String, machineIds: Set<String>, issues: List<InspectionIssue>, note: String, photo: String): JSONObject {
        fun invalid(message: String): Nothing = throw ApiException(422, "invalid_inspection", "client_${UUID.randomUUID()}", message)
        if (building.isBlank() || machineIds.isEmpty()) invalid("请选择有机器的巡检楼栋")
        if (note.length > 500 || issues.any { it.note.length > 500 }) invalid("巡检备注最多 500 字")
        if (issues.map { it.machineId }.distinct().size != issues.size || issues.any { it.machineId !in machineIds }) invalid("巡检机器已变化，请重新核对")
        if (issues.any { it.reasons.isEmpty() || it.reasons.any { reason -> reason !in reasons } }) invalid("请为每台异常机器勾选至少一种情况")
        return JSONObject().put("dormBuilding", building).put("note", note.trim()).put("issues", JSONArray(issues.map {
            JSONObject().put("machineId", it.machineId).put("reasons", JSONArray(reasons.keys.filter { code -> code in it.reasons })).put("note", it.note.trim())
        })).apply { if (photo.isNotBlank()) put("photoData", photo) }
    }
}
