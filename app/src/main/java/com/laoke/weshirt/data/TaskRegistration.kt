package com.laoke.weshirt.data

import org.json.JSONObject
import java.util.UUID

data class WashProgram(val minutes: Int, val label: String, val code: String = "")

/** Keep reminder choices in step with the Web form; these do not operate the appliance. */
object TaskRegistration {
    val washerPrograms = listOf(
        WashProgram(6, "桶自洁 6分钟", "self_clean"),
        WashProgram(30, "标准洗 30分钟"),
        WashProgram(42, "加强洗 42分钟"),
        WashProgram(50, "加强40°C 50分钟"),
        WashProgram(70, "加强60°C 70分钟")
    )

    fun machineTimingAvailable(machine: Machine, now: Long = System.currentTimeMillis()) =
        !machine.estimated && machine.state in setOf("occupied", "abnormal") && machine.localFinishAt()?.let { it > now } == true

    fun validMinutes(kind: String, minutes: Int?) = minutes != null && minutes in (if (kind == "dryer") 10 else 1)..180

    fun body(machine: Machine, minutes: Int, note: String, disinfectant: Boolean, cleaned: Boolean,
             useMachine: Boolean, selfCleanProgram: Boolean, now: Long = System.currentTimeMillis()): JSONObject {
        fun invalid(message: String): Nothing = throw ApiException(422, "invalid_registration",
            "client_" + UUID.randomUUID().toString().replace("-", ""), message)
        if (machine.id.isBlank()) invalid("请先选择机器")
        if (useMachine && !machineTimingAvailable(machine, now)) invalid("机器剩余时间已变化，请返回修改提醒方式")
        if (!useMachine && !validMinutes(machine.kind, minutes)) invalid(if (machine.kind == "dryer") "烘干时长请填10–180分钟" else "洗衣时长请填1–180分钟")
        if (note.codePointCount(0, note.length) > 200) invalid("公开备注最多200字")
        if (selfCleanProgram && (useMachine || machine.kind == "dryer" || minutes != 6)) invalid("桶自洁需选择洗衣机的6分钟程序")
        return JSONObject().put("machineId", machine.id).put("mode", if (useMachine) "machine" else "duration")
            .apply { if (!useMachine) put("minutes", minutes); if (selfCleanProgram) put("program", "self_clean") }
            .put("note", note.trim()).put("useDisinfectant", machine.kind != "dryer" && disinfectant)
            .put("selfCleaned", machine.kind != "dryer" && cleaned)
    }
}
