package com.laoke.weshirt.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun AdminCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable internal fun AdminField(label: String, value: String, changed: (String) -> Unit, enabled: Boolean = true, secret: Boolean = false, multiline: Boolean = false, numeric: Boolean = false) {
    OutlinedTextField(value, { changed(it.take(if (multiline) 24000 else 2048)) }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = !multiline, minLines = if (multiline) 3 else 1,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (numeric) KeyboardType.Number else KeyboardType.Text))
}
@Composable internal fun AdminToggle(label: String, checked: Boolean, changed: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f)); Switch(checked, changed, enabled = enabled)
    }
}
@Composable internal fun AdminChoice(label: String, selected: String, options: List<Pair<String,String>>, changed: (String) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Column { OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label · ${options.find { it.first == selected }?.second ?: selected.ifBlank { "请选择" }}")
    }; DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { expanded = false; changed(id) }) } } }
}
@Composable internal fun AdminAction(label: String, enabled: Boolean = true, confirm: String? = null, action: () -> Unit) {
    var asking by remember(label) { mutableStateOf(false) }
    OutlinedButton(onClick = { if (confirm == null) action() else asking = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text(label) }, text = { Text(confirm.orEmpty()) },
        confirmButton = { TextButton({ asking = false; if (enabled) action() }, enabled = enabled) { Text("确认") } }, dismissButton = { TextButton({ asking = false }) { Text("取消") } })
}
@Composable internal fun AdminFacts(value: JSONObject, fields: List<Pair<String,String>>) {
    fields.forEach { (key,label) ->
        val v = value.opt(key)
        if (v != null && v !== JSONObject.NULL && v !is JSONObject && v !is JSONArray) Text("$label：$v", style = MaterialTheme.typography.bodyMedium)
    }
}
internal val commonFacts = listOf("name" to "名称", "nick" to "昵称", "id" to "编号", "at" to "时间", "state" to "状态", "status" to "处理状态", "message" to "说明", "text" to "内容", "eventId" to "事件", "level" to "级别")
internal fun JSONObject.putNullable(key: String, value: String) = put(key, value.ifBlank { null } ?: JSONObject.NULL)
