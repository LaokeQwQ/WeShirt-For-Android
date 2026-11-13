package com.laoke.weshirt.ui.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.outlined.AutoAwesome
import com.laoke.weshirt.ui.EmptyPanel
import com.laoke.weshirt.ui.SessionRestoringPanel
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import com.laoke.weshirt.ui.NativeMarkdown
import com.laoke.weshirt.ui.user.*
import kotlinx.coroutines.*
import org.json.JSONObject

private data class AssistantConfirmation(val title: String, val message: String, val execute: () -> Unit)

@Composable fun AssistantDialog(state: AppState, vm: AppViewModel, initialSkill: String? = null, close: () -> Unit) {
    if (!state.authReady) {
        UserFeatureDialog("洗衣助手", close) { SessionRestoringPanel(state) { vm.refresh() } }
        return
    }
    key(state.endpoint, state.profile?.id, state.building) { AssistantFeature(state, vm, initialSkill, false, close) }
}

@Composable fun AssistantScreen(state: AppState, vm: AppViewModel, login: () -> Unit) {
    if (!state.authReady) { SessionRestoringPanel(state) { vm.refresh() }; return }
    if (state.profile == null) {
        EmptyPanel("洗衣助手", "登录后可询问空闲机器、洗衣建议，管理自己的记忆与提示词。", Icons.Outlined.AutoAwesome, "钉钉登录", login)
        return
    }
    if (state.access.optJSONObject("permissions")?.optJSONObject("assistant")?.optBoolean("view", true) == false) {
        EmptyPanel("助手暂不可用", "当前账号没有助手查看权限，请联系管理员。", Icons.Outlined.AutoAwesome)
        return
    }
    key(state.endpoint, state.profile.id, state.building) { AssistantFeature(state, vm, null, true, {}) }
}

@Composable private fun AssistantFrame(embedded: Boolean, close: () -> Unit, refresh: () -> Unit, busy: Boolean,
                                      content: @Composable (PaddingValues) -> Unit) {
    if (!embedded) UserFeatureDialog("洗衣助手", close, actions = { IconButton(onClick = refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新助手资料") } }, content = content)
    else Column(Modifier.fillMaxSize().testTag("assistant-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("问问洗衣助手", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新助手资料") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content(PaddingValues()) }
    }
}

@Composable private fun AssistantFeature(state: AppState, vm: AppViewModel, initialSkill: String?, embedded: Boolean, close: () -> Unit) {
    val session = rememberUserFeatureSession(state, vm)
    val context = LocalContext.current
    val snapshot = state.assistant.takeIf { it.scope?.origin == state.endpoint && it.scope?.userId == state.profile?.id } ?: AssistantSnapshot()
    val panels = snapshot.panels
    var status by remember(session) { mutableStateOf(panels["status"]?.data) }
    var messages by remember(session) { mutableStateOf(panels["history"]?.data?.optJSONArray("messages")?.objects().orEmpty()) }
    var skills by remember(session) { mutableStateOf(panels["skills"]?.data?.optJSONArray("skills")?.objects().orEmpty()) }
    var preferences by remember(session) { mutableStateOf(panels["preferences"]?.data) }
    var contextInfo by remember(session) { mutableStateOf<JSONObject?>(null) }
    var memory by remember(session) { mutableStateOf(panels["memory"]?.data) }
    var tab by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf(0) }
    var input by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf("") }
    var note by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf(panels["memory"]?.data?.str("note").orEmpty()) }
    var noteDirty by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf(false) }
    var title by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf("") }
    var description by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf("") }
    var prompt by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf("") }
    var importLink by rememberSaveable(state.endpoint, state.profile?.id) { mutableStateOf("") }
    var shared by remember(session) { mutableStateOf<Pair<String, String>?>(null) }
    var confirmation by remember(session) { mutableStateOf<AssistantConfirmation?>(null) }
    val usage = status?.optJSONObject("usage")
    val canChat = status?.optBoolean("ready") == true && status?.str("dormBuilding").orEmpty().isNotBlank() && (usage?.optInt("dailyRemaining") ?: 0) > 0
    val canSend = canChat && (usage?.optInt("minuteRemaining", 1) ?: 0) > 0 && input.isNotBlank() && input.length <= 1000 && !session.busy

    fun load() { vm.preloadAssistant(force = true) }
    fun updated(vararg names: String, acceptLocal: Boolean = true) {
        val keys = names.toSet()
        if (acceptLocal) keys.forEach { name ->
            val confirmed = when (name) {
                "status" -> status
                "history" -> JSONObject().put("messages", org.json.JSONArray(messages))
                "skills" -> JSONObject().put("skills", org.json.JSONArray(skills))
                "memory" -> memory
                "preferences" -> preferences
                else -> null
            }
            confirmed?.let { vm.acceptAssistantPanel(name, it) }
        }
        vm.invalidateAssistant(keys)
        vm.preloadAssistant(force = true, names = keys)
    }
    // Each response becomes visible independently. Optional metadata never
    // holds the conversation behind a single session-wide busy flag.
    LaunchedEffect(session, panels["status"]) { panels["status"]?.takeIf { !it.loading && it.error == null }?.data?.let { status = it } }
    LaunchedEffect(session, panels["history"]) { panels["history"]?.takeIf { !it.loading && it.error == null }?.data?.let { messages = it.optJSONArray("messages")?.objects().orEmpty() } }
    LaunchedEffect(session, panels["skills"]) { panels["skills"]?.takeIf { !it.loading && it.error == null }?.data?.let { skills = it.optJSONArray("skills")?.objects().orEmpty() } }
    LaunchedEffect(session, panels["preferences"]) { panels["preferences"]?.takeIf { !it.loading && it.error == null }?.data?.let { preferences = it } }
    LaunchedEffect(session, panels["memory"]) { panels["memory"]?.takeIf { !it.loading && it.error == null }?.data?.let { memory = it; if (!noteDirty) note = it.str("note") } }
    fun updateMemory(body: JSONObject, savedNote: Boolean = false) { session.run {
        val data = request("/api/assistant/memory", "PATCH", body)
        memory = data
        if (savedNote) { note = data.str("note"); noteDirty = false }
        updated("memory")
    } }
    LaunchedEffect(session) { vm.preloadAssistant() }
    LaunchedEffect(session, initialSkill) {
        if (!initialSkill.isNullOrBlank()) {
            val incoming = ShareLinks.ticket(initialSkill, "skill")
            if (incoming == null) session.error = "Skill 分享链接格式不正确，请重新获取"
            else { importLink = incoming; tab = 1 }
        }
    }
    // The per-minute allowance can recover while this screen stays open; daily exhaustion does not poll.
    LaunchedEffect(session, usage?.optInt("minuteRemaining", 1), usage?.optInt("dailyRemaining", 0)) {
        if ((usage?.optInt("dailyRemaining") ?: 0) > 0 && usage?.optInt("minuteRemaining", 1) == 0) {
            delay(60_000)
            vm.preloadAssistant(force = true, names = setOf("status"))
        }
    }

    AssistantFrame(embedded, close, ::load, session.busy) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("对话", "技能与分享", "个人记忆", "只读 MCP", "提示词").forEachIndexed { index, label -> FilterChip(selected = tab == index, onClick = { tab = index }, modifier = Modifier.testTag("assistant-tab:$index"), label = { Text(label) }) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { FeatureError(session) }
                item { AssistantPanelFeedback("status", panels["status"], "助手状态") { vm.preloadAssistant(force = true, names = setOf("status")) } }
                status?.let { info -> item {
                    Card(Modifier.fillMaxWidth().testTag("assistant-status-card")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(info.str("buildingName", "当前住宿楼"), style = MaterialTheme.typography.titleMedium)
                        Text("今日剩余 ${usage?.optInt("dailyRemaining") ?: 0} / ${usage?.optInt("dailyLimit") ?: 0} 次", style = MaterialTheme.typography.bodySmall)
                        when {
                            !info.optBoolean("ready") -> Text("AI 助手尚未开放，管理员完成模型配置后即可提问。", color = MaterialTheme.colorScheme.tertiary)
                            info.str("dormBuilding").isBlank() -> Text("请先在设置中绑定住宿楼，助手会查询你所在楼栋的机器。")
                            (usage?.optInt("dailyRemaining") ?: 0) <= 0 -> Text("今日额度已用完，明天再来；看板仍可正常查询。")
                            usage?.optInt("minuteRemaining", 1) == 0 -> Text("提问较频繁，请稍等一分钟后重试。")
                        }
                    } }
                } }
                when (tab) {
                    0 -> {
                        item { AssistantPanelFeedback("history", panels["history"], "聊天记录") { vm.preloadAssistant(force = true, names = setOf("history")) } }
                        if(contextInfo?.optBoolean("compressed")==true)item{Text("较早的对话已自动压缩，近期问题保留原文；需要时请补充细节。",style=MaterialTheme.typography.bodySmall)}
                        item { Text("技能已自动启用，会按问题使用洗衣知识和当前楼栋只读查询，无需选择。",style=MaterialTheme.typography.bodySmall) }
                        if (messages.isEmpty() && panels["history"]?.data != null && panels["history"]?.loading != true) item { Text("可以询问空闲机器、洗衣建议或你的洗衣安排。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        itemsIndexed(messages) { index, message ->
                            val mine = message.str("role") == "user"
                            Card(Modifier.fillMaxWidth().testTag("assistant-message:$index"), colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (mine) "你" else "洗衣助手", style = MaterialTheme.typography.labelSmall)
                                    if (mine) Text(message.str("content")) else NativeMarkdown(message.str("content"))
                                    TextButton(onClick = { copyFeatureText(context, message.str("content")); vm.message("已复制") }) { Text("复制") }
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(input, { input = it.take(1000) }, label = { Text("想问什么？") }, enabled = !session.busy, supportingText = { Text("${input.length}/1000") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(enabled = !session.busy && messages.isNotEmpty(), onClick = {
                                        confirmation = AssistantConfirmation("清空对话？", "会删除当前账号保存的聊天记录，个人记忆单独管理。") {
                                            session.run { request("/api/assistant/history", "DELETE"); messages = emptyList(); updated("history") }
                                        }
                                    }) { Text("清空对话") }
                                    Button(enabled = canSend, onClick = {
                                        val submitted = input.trim()
                                        val body = JSONObject().put("input", submitted)
                                        session.run {
                                            try {
                                                val data = request("/api/assistant/chat", "POST", body)
                                                messages = data.optJSONArray("messages")?.objects().orEmpty(); contextInfo = data.optJSONObject("context")
                                                status = JSONObject(status?.toString() ?: "{}").put("usage", data.optJSONObject("usage"))
                                                updated("history", "status")
                                                if (input.trim() == submitted) input = ""
                                            } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) {
                                                // Reconcile spent quota after provider failure; keep the typed question intact.
                                                updated("status", acceptLocal = false)
                                                throw failure
                                            }
                                        }
                                    }) { Text(if (session.busy) "请稍候…" else "发送") }
                                }
                            }
                        }
                    }
                    1 -> {
                        item { AssistantPanelFeedback("skills", panels["skills"], "技能与分享") { vm.preloadAssistant(force = true, names = setOf("skills")) } }
                        item { Text("Skill 是可复用的提问模板，分享内容不包含聊天、个人记忆或登录凭证。", style = MaterialTheme.typography.bodySmall) }
                        items(skills, key = { it.str("id") }) { skill ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(skill.str("title"), style = MaterialTheme.typography.titleMedium)
                                Text(if (skill.optBoolean("builtin")) "内置模板" else "自定义模板", style = MaterialTheme.typography.labelSmall)
                                if (skill.str("description").isNotBlank()) Text(skill.str("description"))
                                Text(skill.str("prompt"), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    TextButton(onClick = { copyFeatureText(context, skill.str("prompt")); vm.message("模板已复制") }) { Text("复制") }
                                    TextButton(enabled = !session.busy, onClick = { title = skill.str("title"); description = skill.str("description"); prompt = skill.str("prompt") }) { Text("创建副本") }
                                    TextButton(enabled = !session.busy, onClick = { session.run {
                                        val data = request("/api/assistant/skills/${vm.api.encoded(skill.str("id"))}/share", "POST", JSONObject())
                                        shared = skill.str("title") to ShareLinks.skill(state.endpoint, data.str("ticket"))
                                    } }) { Text("分享") }
                                    if (!skill.optBoolean("builtin")) TextButton(enabled = !session.busy, onClick = {
                                        confirmation = AssistantConfirmation("删除这个 Skill？", skill.str("title")) {
                                            session.run {
                                                request("/api/assistant/skills/${vm.api.encoded(skill.str("id"))}", "DELETE")
                                                skills = skills.filterNot { it.str("id") == skill.str("id") }
                                                updated("skills")
                                            }
                                        }
                                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                            } }
                        }
                        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("新建 Skill", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(title, { title = it.take(40) }, label = { Text("标题（1–40 字）") }, enabled = !session.busy, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(description, { description = it.take(120) }, label = { Text("说明（选填）") }, enabled = !session.busy, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(prompt, { prompt = it.take(1500) }, label = { Text("提问模板（1–1500 字）") }, minLines = 3, maxLines = 8, enabled = !session.busy, modifier = Modifier.fillMaxWidth())
                            Button(enabled = !session.busy && title.isNotBlank() && prompt.isNotBlank(), onClick = {
                                val body = JSONObject().put("title", title.trim()).put("description", description.trim()).put("prompt", prompt.trim())
                                session.run {
                                    val created = request("/api/assistant/skills", "POST", body).optJSONObject("skill") ?: throw IllegalStateException("模板保存结果不完整，请刷新确认")
                                    skills = skills + created
                                    updated("skills")
                                    title = ""; description = ""; prompt = ""; vm.message("Skill 已保存")
                                }
                            }) { Text("保存模板") }
                            HorizontalDivider()
                            Text("导入分享的 Skill", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(importLink, { importLink = it.take(12_000) }, label = { Text("分享链接或分享码") }, maxLines = 4, enabled = !session.busy, modifier = Modifier.fillMaxWidth())
                            Button(enabled = !session.busy && importLink.isNotBlank(), onClick = {
                                val ticket = ShareLinks.ticket(importLink, "skill")
                                if (ticket == null) session.error = "分享链接格式不正确，请粘贴完整的 Skill 链接"
                                else session.run {
                                    val created = request("/api/assistant/skills/import", "POST", JSONObject().put("ticket", ticket)).optJSONObject("skill") ?: throw IllegalStateException("导入结果不完整，请刷新确认")
                                    skills = skills + created; updated("skills"); importLink = ""; vm.message("已导入你的 Skill 副本")
                                }
                            }) { Text("导入副本") }
                        } }
                    }
                    2 -> {
                        item { AssistantPanelFeedback("memory", panels["memory"], "个人记忆") { vm.preloadAssistant(force = true, names = setOf("memory")) } }
                        item {
                            val currentMemory = memory
                            if (currentMemory == null) {
                                if (panels["memory"]?.loading != true && panels["memory"]?.error == null) Text("个人记忆尚未读取。")
                            }
                            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("使用个人记忆", style = MaterialTheme.typography.titleMedium)
                                    Switch(currentMemory.optBoolean("enabled"), { updateMemory(JSONObject().put("enabled", it)) }, enabled = !session.busy)
                                }
                                Text("开启后会使用你的备注及近期登记习惯回答；关闭后保留资料，但不会用于回答。", style = MaterialTheme.typography.bodySmall)
                                val habits = currentMemory.optJSONObject("habits")
                                val hours = habits?.optJSONArray("commonHours")
                                Text(if (currentMemory.optBoolean("enabled") && (habits?.optInt("count") ?: 0) > 0)
                                    "常用时段（北京时间）：${(0 until (hours?.length() ?: 0)).joinToString("、") { String.format(java.util.Locale.ROOT, "%02d:00", hours!!.optInt(it)) }}，来自近 14 天的 ${habits?.optInt("count")} 次登记。"
                                else "登记记录积累后，会显示你的常用洗衣时段。")
                                OutlinedTextField(note, { note = it.take(500); noteDirty = true }, label = { Text("希望助手记住（选填）") }, minLines = 3, maxLines = 8, enabled = !session.busy, supportingText = { Text("${note.length}/500") }, modifier = Modifier.fillMaxWidth())
                                Button(enabled = !session.busy, onClick = { updateMemory(JSONObject().put("note", note), true) }) { Text("保存备注") }
                                OutlinedButton(enabled = !session.busy, onClick = {
                                    confirmation = AssistantConfirmation("清空并关闭个人记忆？", "将删除备注并关闭记忆。以后重新开启时，仅从清空之后的新登记生成习惯。") {
                                        session.run { memory = request("/api/assistant/memory", "DELETE"); note = ""; noteDirty = false; updated("memory") }
                                    }
                                }) { Text("清空并关闭记忆", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    4 -> item {
                        AssistantPanelFeedback("preferences", panels["preferences"], "提示词") { vm.preloadAssistant(force = true, names = setOf("preferences")) }
                        preferences?.let { current -> AssistantPromptEditor(current,session.busy,save={body->session.run{preferences=request("/api/assistant/preferences","PATCH",body);updated("preferences");vm.message("已保存并使用此提示词")}},reset={confirmation=AssistantConfirmation("恢复平台默认提示词？","将删除当前账号的自定义模板，可重新编辑。") {session.run{preferences=request("/api/assistant/preferences","PATCH",JSONObject().put("reset",true));updated("preferences")}}}) }
                    }
                    3 -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("个人只读 MCP", style = MaterialTheme.typography.titleMedium)
                            Text("支持 JSON-RPC HTTP，可读取当前绑定住宿楼的机器状态，不提供写入或通知工具。")
                            Text(state.endpoint.trimEnd('/') + "/api/mcp")
                            Text("外部客户端需自行配置个人 Bearer 登录凭证。应用不会复制或分享你的登录凭证。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { copyFeatureText(context, state.endpoint.trimEnd('/') + "/api/mcp"); vm.message("MCP 地址已复制") }) { Text("仅复制接口地址") }
                        }
                    }
                }
            }
        }
    }
    shared?.let { ShareCodeDialog(it.first, it.second, "仅包含 Skill 模板，7 天内可导入，不包含聊天或个人记忆。") { shared = null } }
    confirmation?.let { ask -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(ask.title) }, text = { Text(ask.message) }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }, confirmButton = { TextButton(enabled = !session.busy, onClick = { confirmation = null; ask.execute() }) { Text("确认") } }) }
}

@Composable
internal fun AssistantPanelFeedback(name: String, panel: AssistantPanel?, title: String, retry: () -> Unit) {
    if (panel?.loading == true) {
        Text(if (panel.data == null) "正在读取$title…" else "正在更新$title…",
            Modifier.testTag("assistant-loading:$name"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    panel?.error?.let { failure ->
        Surface(Modifier.fillMaxWidth().testTag("assistant-error:$name"), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${title}暂未更新", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = retry, enabled = !panel.loading, modifier = Modifier.testTag("assistant-retry:$name")) { Text("重试$title") }
            }
        }
    }
}
