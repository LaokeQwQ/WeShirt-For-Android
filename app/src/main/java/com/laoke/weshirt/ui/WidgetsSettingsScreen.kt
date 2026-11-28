@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.laoke.weshirt.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.laoke.weshirt.platform.widget.LaundryWidgetRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class AndroidWidgetsManager(private val context: Context) {
    fun read(): WidgetsManagementState {
        val manager = AppWidgetManager.getInstance(context)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = runCatching { context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        val entries = LaundryWidgetRegistry.entries.map { entry ->
            val size = entry.create().widgetSize
            WidgetEntryStatus(entry.providerClass.name, size.columns, size.rows,
                manager.getAppWidgetIds(ComponentName(context, entry.providerClass)).size)
        }
        return WidgetsManagementState(entries, manager.isRequestPinAppWidgetSupported,
            detectWidgetDesktop(Build.MANUFACTURER, Build.BRAND, launcher?.activityInfo?.packageName),
            runCatching { launcher?.loadLabel(context.packageManager)?.toString().orEmpty() }.getOrDefault(""))
    }

    fun add(key: String): Boolean {
        val entry = LaundryWidgetRegistry.entries.firstOrNull { it.providerClass.name == key } ?: return false
        val manager = AppWidgetManager.getInstance(context)
        return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, entry.providerClass), null, null)
    }
}

@Composable internal fun WidgetsSettingsScreen(close: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { AndroidWidgetsManager(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<WidgetsManagementState?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var lastPinAt by remember { mutableLongStateOf(0L) }
    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            try { state = withContext(Dispatchers.IO) { manager.read() } }
            catch (_: RuntimeException) { info = "暂时无法读取桌面组件，仍可按下方步骤添加。\n状态码 0 · 事件 client_widget_${UUID.randomUUID().toString().replace("-", "")}" }
            finally { loading = false }
        }
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { refresh() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = close)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = { TopAppBar(title = { Text("桌面小组件") }, navigationIcon = {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            }, actions = { IconButton(onClick = ::refresh, enabled = !loading) { Icon(Icons.Outlined.Refresh, "刷新已添加数量") } }) }) { padding ->
                WidgetsSettingsContent(state ?: WidgetsManagementState(emptyList(), false, WidgetDesktopFamily.GENERIC),
                    info = info, modifier = Modifier.padding(padding), onAdd = { entry ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPinAt >= 1500) {
                            lastPinAt = now
                            info = try {
                                if (manager.add(entry.key)) "已请求系统添加，请在桌面弹窗中确认；确认前不会计入已添加数量。"
                                else "当前桌面未打开添加弹窗，请按下方指引手动添加。"
                            } catch (_: RuntimeException) { "添加请求未打开，请按下方指引手动添加。\n状态码 0 · 事件 client_widget_${UUID.randomUUID().toString().replace("-", "")}" }
                        }
                    }, onHome = {
                        try { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: RuntimeException) { info = "请使用系统手势或主屏幕键返回桌面。" }
                    })
            }
        }
    }
}

@Composable internal fun WidgetsSettingsContent(
    state: WidgetsManagementState, info: String? = null, modifier: Modifier = Modifier,
    onAdd: (WidgetEntryStatus) -> Unit, onHome: () -> Unit,
) {
    var familyName by rememberSaveable { mutableStateOf<String?>(null) }
    val family = WidgetDesktopFamily.entries.firstOrNull { it.name == familyName } ?: state.detected
    var chooseFamily by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().testTag("widgets-management"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Widgets, null, Modifier.size(30.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("桌面上的洗衣进度", style = MaterialTheme.typography.titleLarge)
                    Text("外观跟随应用，洗衣时自动更新。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } }
        if (info != null) item { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(info, Modifier.padding(14.dp).testTag("widget-management-feedback")) } }
        items(state.entries, key = { it.key }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("widget-size:${entry.columns}x${entry.rows}"), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 64.dp, height = 58.dp), contentAlignment = Alignment.Center) {
                        Surface(Modifier.size(width = if (entry.columns == 4) 62.dp else 44.dp,
                            height = if (entry.rows == 1) 25.dp else 44.dp), shape = RoundedCornerShape(9.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.LocalLaundryService, null, Modifier.size(18.dp)) }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.sizeLabel, style = MaterialTheme.typography.titleMedium)
                        Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.installed > 0) Text("已添加 ${entry.installed} 个", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    FilledTonalIconButton(onClick = { onAdd(entry) }, enabled = state.pinSupported,
                        modifier = Modifier.size(48.dp).testTag("widget-add:${entry.columns}x${entry.rows}")) {
                        Icon(Icons.Outlined.Add, "添加 ${entry.sizeLabel} 小组件")
                    }
                }
            }
        }
        item {
            Text(if (state.pinSupported) "由系统弹窗确认添加，是否成功以桌面实际显示为准。" else "当前桌面不支持应用内直接添加，请使用下面的手动添加指引。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("添加指引", style = MaterialTheme.typography.titleLarge)
            if (state.launcherName.isNotBlank()) Text("当前桌面：${state.launcherName}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { chooseFamily = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("widget-guide-family")) {
                Column(Modifier.weight(1f)) { Text(if (familyName == null) "按设备推荐，可手动切换" else "已手动选择"); Text(family.label, style = MaterialTheme.typography.bodySmall) }
                Icon(Icons.Outlined.ExpandMore, null)
            }
            family.steps.forEachIndexed { index, step -> Text("${index + 1}. $step", Modifier.padding(top = 10.dp).testTag("widget-guide-step:$index")) }
            if (family.hint.isNotBlank()) Text(family.hint, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
            Text("菜单名称可能随系统版本和桌面应用变化；找不到时可切换为通用指引。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = onHome, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) { Text("回到桌面添加") }
        }
        item {
            Text("移动与移除", style = MaterialTheme.typography.titleMedium)
            Text("在桌面长按 WeShirt 小组件，可拖动调整位置，或选择「移除」。移除组件不会删除洗衣记录。", Modifier.padding(top = 8.dp))
            Text("在系统组件列表搜索 WeShirt，再滑动浏览尺寸。找不到 2×2 时，检查桌面是否有连续两行两列的空位，也可以从上方点击 2×2 旁的加号。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (chooseFamily) AlertDialog(onDismissRequest = { chooseFamily = false }, title = { Text("选择桌面指引") }, text = {
        LazyColumn(Modifier.heightIn(max = 430.dp)) {
            item { TextButton(onClick = { familyName = null; chooseFamily = false }, modifier = Modifier.fillMaxWidth()) { Text("按设备推荐") } }
            items(WidgetDesktopFamily.entries) { option -> TextButton(onClick = { familyName = option.name; chooseFamily = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("widget-guide-option:${option.name}")) { Text(option.label) } }
        }
    }, confirmButton = { TextButton(onClick = { chooseFamily = false }) { Text("返回") } })
}
