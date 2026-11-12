package com.laoke.weshirt.ui.admin

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONObject

@Composable internal fun AdminMachineTimePanel(c: AdminController, operate: Boolean) {
    val path = "/api/admin/machine-time-calibration"
    LaunchedEffect(c) { c.load(path) }
    var selected by remember { mutableStateOf("") }
    val machines = c.document.arrayRows("machines")
    val machine = machines.find { it.text("id") == selected }
    var offset by remember(selected, machine?.toString()) { mutableStateOf(machine?.optInt("offsetMinutes")?.toString() ?: "0") }
    var auto by remember(selected, machine?.toString()) { mutableStateOf(machine?.optBoolean("autoEnabled") == true) }
    val enabled = operate && machine?.optBoolean("canOperate") == true && !c.saving && !c.loading
    AdminCard("机器时间校准") {
        Text("按最新平台时间同步。正数推迟、负数提前，单位为分钟。")
        AdminChoice("机器", selected, machines.map { it.text("id") to "${it.text("displayName")} · ${it.text("dormBuilding")}" }, { selected = it }, !c.saving)
        if (machine != null) {
            AdminField("手动校准（−120 至 +120）", offset, { offset = it.take(4) }, enabled)
            AdminToggle("自动校时", auto, { auto = it }, enabled)
            Text("自动学习 ${machine.optInt("learnedOffsetMinutes")} 分钟 · 有效校准 ${machine.optInt("effectiveOffsetMinutes")} 分钟 · ${machine.optInt("sampleCount")} 个有效周期")
            Text("自动学习仅采用从开始到实际完成均被观察到的完整周期，网络失败和机器异常不计入。", style = MaterialTheme.typography.bodySmall)
            fun save(reset: Boolean) {
                val minutes = offset.toIntOrNull()
                if (minutes == null || minutes !in -120..120) c.localError("请填写 −120 至 +120 的整数分钟")
                else c.act("$path/${c.api.encoded(selected)}", "PATCH", JSONObject().put("offsetMinutes", minutes).put("autoEnabled", auto).put("resetLearning", reset), refresh = path)
            }
            AdminAction("保存校时", enabled) { save(false) }
            AdminAction("重置学习记录", enabled, "清除此机器的自动学习记录，保留手动校准值。确认重置？") { save(true) }
        }
        AdminAction("刷新校时数据", !c.saving && !c.loading) { c.load(path) }
    }
}
