package com.laoke.weshirt.ui.admin

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun AdminAssistantPanel(c: AdminController, operate: Boolean) {
    val path = "/api/admin/assistant"
    LaunchedEffect(Unit) { c.load(path) }
    val d = c.document
    val stamp = d.toString()
    var enabled by remember(stamp) { mutableStateOf(d.optBoolean("enabled")) }
    var protocol by remember(stamp) { mutableStateOf(d.text("protocol").ifBlank { "responses" }) }
    var endpoint by remember(stamp) { mutableStateOf(d.text("endpoint")) }
    var model by remember(stamp) { mutableStateOf(d.text("model")) }
    var daily by remember(stamp) { mutableStateOf(d.optInt("dailyLimit", 1000).toString()) }
    var minute by remember(stamp) { mutableStateOf(d.optInt("minuteLimit", 30).toString()) }
    var reasoning by remember(stamp) { mutableStateOf(d.text("reasoningMode").ifBlank { "auto" }) }
    var effort by remember(stamp) { mutableStateOf(d.text("reasoningEffort").ifBlank { "auto" }) }
    var thinking by remember(stamp) { mutableStateOf(d.text("thinkingMode").ifBlank { "auto" }) }
    var capabilities by remember(stamp) { mutableStateOf(d.optJSONObject("modelCapabilities") ?: JSONObject()) }
    var models by remember(stamp) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var profiles by remember(stamp) { mutableStateOf(d.arrayRows("promptProfiles")) }
    var active by remember(stamp) { mutableStateOf(d.text("activePromptId")) }
    val selected = profiles.find { it.text("id") == active }
    var promptName by remember(stamp, active) { mutableStateOf(selected?.text("name").orEmpty()) }
    var promptText by remember(stamp, active) { mutableStateOf(selected?.text("text").orEmpty()) }
    var key by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    fun editedProfiles() = profiles.map { if (it.text("id") == active) it.copyObject().put("name",promptName.trim()).put("text",promptText.trim()) else it.copyObject() }
    DisposableEffect(Unit) { onDispose { key = "" } }
    AdminAction("刷新模型设置", !c.saving) { c.load(path) }
    if (d.length() > 0) AdminCard("模型与协议") {
        val edit = operate && !c.saving && !c.loading
        AdminToggle("启用洗衣助手", enabled, { enabled = it }, edit)
        AdminChoice("API 协议",protocol,listOf("responses" to "OpenAI Responses","chat-completions" to "OpenAI Chat Completions","anthropic" to "Anthropic Messages"),{protocol=it;capabilities=JSONObject();models=emptyList();effort="auto"},edit)
        AdminField("HTTPS 基础地址或完整端点",endpoint,{endpoint=it;models=emptyList()},edit)
        Text("API 密钥：${if(d.optBoolean("keyConfigured")) "已配置" else "未配置"}，留空保留原值。")
        AdminField("替换 API 密钥",key,{key=it.take(512)},edit && !clear,secret=true)
        AdminToggle("清除现有密钥",clear,{clear=it},edit)
        AdminAction("获取可用模型",edit && !clear && (key.isNotBlank() || d.optBoolean("keyConfigured"))) {
            val body=JSONObject().put("protocol",protocol).put("endpoint",endpoint.trim())
            if(key.isNotBlank())body.put("apiKey",key.trim())
            c.act("$path/models","POST",body,refresh=null,success={result->models=result.arrayRows("models");if(models.isEmpty())c.localError("服务未返回模型，请手动填写模型 ID")})
        }
        if(models.isNotEmpty())AdminChoice("可用模型",model,models.map{it.text("id") to it.text("name")},{id->model=id;capabilities=models.find{it.text("id")==id}?.optJSONObject("capabilities") ?: JSONObject();effort="auto"},edit)
        AdminField("模型 ID（支持手填）",model,{model=it.take(120);capabilities=JSONObject();effort="auto"},edit)
        Text("模型发现最多读取300条；接口不支持列表时可手填。")
        AdminChoice("思考模式",reasoning,listOf("auto" to "自动匹配能力","off" to "关闭（模型支持时）","on" to "开启（已确认支持）"),{reasoning=it},edit)
        val supported = capabilities.optJSONArray("effortLevels")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val efforts=if(supported.isEmpty())listOf("none","minimal","low","medium","high","xhigh","max")else supported
        AdminChoice("思考强度",effort,listOf("auto" to "模型默认")+efforts.map{it to it},{effort=it},edit && reasoning!="off")
        Text(when { capabilities.isNull("reasoning") || !capabilities.has("reasoning") -> "服务未声明能力，自动模式不会为未知模型添加思考参数。"; capabilities.optBoolean("reasoning") -> "该模型支持思考参数。"; else -> "该模型未声明支持思考。" })
        if(protocol=="anthropic")AdminChoice("Claude 思考方式",thinking,listOf("auto" to "自动匹配","adaptive" to "Adaptive","budget" to "Token budget"),{thinking=it},edit)
        AdminField("每日提问额度（1–10000）",daily,{daily=it},edit,numeric=true)
        AdminField("每分钟模型请求（1–30）",minute,{minute=it},edit,numeric=true)
        Text("每分钟30次为硬上限，工具续问和失败也计数。上下文最多128k，自动提取压缩早期对话。Skills 和当前楼栋的只读 MCP 自动可用。")
        AdminChoice("平台默认提示词",active,profiles.map{it.text("id") to it.text("name")},{id->profiles=editedProfiles();active=id},edit)
        AdminField("模板名称",promptName,{promptName=it.take(40)},edit)
        AdminField("模板内容",promptText,{promptText=it.take(6000)},edit,multiline=true)
        AdminAction("新建提示词模板",edit && profiles.size<8){val row=JSONObject().put("id","prompt-"+java.util.UUID.randomUUID()).put("name","新模板").put("text","请简洁说明洗衣安排。");profiles=editedProfiles()+row;active=row.text("id")}
        AdminAction("删除当前模板",edit && profiles.size>1,"删除后需保存模型设置才会生效。"){profiles=profiles.filter{it.text("id")!=active};active=profiles.first().text("id")}
        Text("用户可编辑、切换自己的模板；平台模板不会授予任何写入工具。")
        AdminAction("保存模型设置",edit,"将更新协议、模型、默认模板和调用额度。"){
            val a=daily.toIntOrNull();val b=minute.toIntOrNull()
            if(a==null || b==null || a !in 1..10000 || b !in 1..30 || Uri.parse(endpoint).scheme!="https" || model.isBlank())c.localError("请检查 HTTPS 端点、模型 ID 和调用额度")
            else {
                val body=JSONObject().put("enabled",enabled).put("protocol",protocol).put("endpoint",endpoint.trim()).put("model",model.trim()).put("dailyLimit",a).put("minuteLimit",b).put("reasoningMode",reasoning).put("reasoningEffort",effort).put("thinkingMode",thinking).put("modelCapabilities",capabilities).put("promptProfiles",JSONArray(editedProfiles())).put("activePromptId",active)
                try { c.act(path,"PATCH",assistantConfigurationPayload(body,key,clear),finished={key="";clear=false}) }
                catch(failure:IllegalArgumentException) { c.localError(failure.message ?: "模型设置格式无效") }
            }
        }
    }
}
