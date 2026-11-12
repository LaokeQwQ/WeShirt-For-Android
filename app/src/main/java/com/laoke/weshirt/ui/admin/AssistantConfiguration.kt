package com.laoke.weshirt.ui.admin

import org.json.JSONObject
import java.net.URI

/** Used by the native form before sending. Server validation remains authoritative. */
internal fun assistantConfigurationPayload(form: JSONObject, replacementKey: String = "", clearKey: Boolean = false): JSONObject {
    val result = JSONObject(form.toString())
    require(result.optString("protocol") in setOf("responses", "chat-completions", "anthropic")) { "请选择有效的 API 协议" }
    val endpoint = result.optString("endpoint").trim()
    val uri = runCatching { URI(endpoint) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in setOf(-1,443)) { "API 地址须为不含账号或查询参数的 HTTPS 地址" }
    val model = result.optString("model").trim()
    require(model.isNotEmpty() && model.length <= 120 && model.none { it.code < 32 }) { "请填写有效模型 ID" }
    require(result.optInt("dailyLimit") in 1..10000 && result.optInt("minuteLimit") in 1..30) { "每日额度须为1–10000，每分钟须为1–30" }
    require(result.optString("reasoningMode") in setOf("auto","off","on") && result.optString("reasoningEffort") in setOf("auto","none","minimal","low","medium","high","xhigh","max")) { "思考参数无效" }
    val profiles = result.optJSONArray("promptProfiles")
    require(profiles != null && profiles.length() in 1..8) { "提示词模板须为1–8套" }
    val ids = mutableSetOf<String>()
    for (index in 0 until profiles.length()) {
        val row = requireNotNull(profiles.optJSONObject(index)) { "提示词格式无效" }
        val id = row.optString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,60}")) && ids.add(id) && row.optString("name").trim().length in 1..40 && row.optString("text").trim().length in 1..6000) { "提示词名称、内容或编号无效" }
    }
    require(result.optString("activePromptId") in ids) { "请选择存在的默认提示词" }
    result.put("endpoint",endpoint).put("model",model)
    result.remove("apiKey"); result.remove("clearKey")
    if (clearKey) result.put("clearKey",true)
    else if (replacementKey.isNotBlank()) {
        val key = replacementKey.trim()
        require(key.length <= 512 && key.none { it.code <= 32 || it.code == 127 }) { "API 密钥格式无效" }
        result.put("apiKey",key)
    }
    return result
}
