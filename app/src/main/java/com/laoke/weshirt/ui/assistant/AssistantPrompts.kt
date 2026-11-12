package com.laoke.weshirt.ui.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.data.objects
import com.laoke.weshirt.data.str
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun AssistantPromptEditor(preferences: JSONObject, busy: Boolean, save: (JSONObject) -> Unit, reset: () -> Unit) {
    val stamp=preferences.toString()
    var profiles by remember(stamp){mutableStateOf(preferences.optJSONArray("promptProfiles")?.objects().orEmpty())}
    var active by remember(stamp){mutableStateOf(preferences.str("activePromptId"))}
    val selected=profiles.find{it.str("id")==active}
    var name by remember(stamp,active){mutableStateOf(selected?.str("name").orEmpty())}
    var text by remember(stamp,active){mutableStateOf(selected?.str("text").orEmpty())}
    fun drafts()=profiles.map{if(it.str("id")==active)JSONObject(it.toString()).put("name",name.trim()).put("text",text.trim())else JSONObject(it.toString())}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("我的回答风格与提示词",style=MaterialTheme.typography.titleMedium)
        Text("模板只保存在你的账号，可从平台默认模板修改。选择或编辑后点击保存并使用。",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            profiles.forEach{row->FilterChip(selected=active==row.str("id"),onClick={profiles=drafts();active=row.str("id")},label={Text(row.str("name"))},enabled=!busy)}
        }
        OutlinedTextField(name,{name=it.take(40)},label={Text("模板名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(text,{text=it.take(6000)},label={Text("提示词")},minLines=5,maxLines=12,enabled=!busy,supportingText={Text("${text.length}/6000")},modifier=Modifier.fillMaxWidth())
        Button(onClick={save(JSONObject().put("promptProfiles",JSONArray(drafts())).put("activePromptId",active))},enabled=!busy && name.isNotBlank() && text.isNotBlank()){Text("保存并使用")}
        OutlinedButton(onClick={val row=JSONObject().put("id","personal-"+java.util.UUID.randomUUID()).put("name","我的模板").put("text",text.ifBlank{"请简洁说明洗衣安排。"});profiles=drafts()+row;active=row.str("id")},enabled=!busy && profiles.size<8){Text("新建模板")}
        OutlinedButton(onClick={profiles=profiles.filter{it.str("id")!=active};active=profiles.first().str("id")},enabled=!busy && profiles.size>1){Text("删除此模板（保存后生效）")}
        TextButton(onClick=reset,enabled=!busy){Text("恢复平台默认模板")}
        Text("提示词不能扩大工具权限。机器状态始终重新查询，Skills 与当前住宿楼只读 MCP 自动可用。",style=MaterialTheme.typography.bodySmall)
    }
}
