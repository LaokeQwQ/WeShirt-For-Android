package com.laoke.weshirt.ui

import java.util.Locale

internal enum class WidgetDesktopFamily(val label: String, val steps: List<String>, val hint: String = "") {
    ORIGIN("vivo / iQOO · OriginOS", listOf("回到桌面，长按空白处或双指捏合。", "进入「原子组件」或「组件」，找到 WeShirt。", "选择所需尺寸，长按拖到桌面空白处。"), "部分版本可从桌面上滑进入组件页；请在应用组件中查找 WeShirt。"),
    XIAOMI("小米 / Redmi / POCO · HyperOS / MIUI", listOf("回到桌面，长按空白处或双指捏合。", "进入「添加小部件」，查找 WeShirt。", "选择所需尺寸，添加或拖到空白桌面。"), "若首先进入组件推荐页，请继续查找「全部应用」「安卓小部件」或搜索 WeShirt。"),
    OPPO("OPPO · ColorOS", listOf("回到桌面，长按空白处或双指捏合。", "点击「插件」「小部件」或左上角的加号，找到 WeShirt。", "选择所需尺寸，长按拖到桌面空白处。")),
    REALME("realme · realme UI", listOf("回到桌面，长按空白处。", "点击左上角加号或底部「小部件」，找到 WeShirt。", "选择所需尺寸，放到有足够空间的桌面。")),
    ONEPLUS("一加 · OxygenOS", listOf("回到桌面，长按空白处。", "进入「小部件 / Widgets」，找到 WeShirt。", "选择所需尺寸，长按拖到桌面空白处。"), "使用 ColorOS 的一加机型可改选 ColorOS 指引。"),
    HUAWEI("华为 · HarmonyOS / EMUI", listOf("回到桌面，双指捏合进入编辑。", "进入「服务卡片」，向下找到「窗口小工具」；旧版本可直接进入「窗口小工具」。", "找到 WeShirt，选择尺寸，长按后添加到桌面。"), "适用于可运行本 Android 版的系统；此组件是 Android 窗口小工具，入口可能与系统服务卡片分开。"),
    HONOR("荣耀 · MagicOS / Magic UI", listOf("回到桌面，双指捏合进入编辑。", "进入「桌面卡片」，在底部查找「经典小工具」；旧版本使用「窗口小工具」。", "找到 WeShirt，选择尺寸，点击或拖到桌面空白处。")),
    SAMSUNG("三星 · One UI", listOf("回到主屏幕，长按空白处。", "进入「小组件 / Widgets」，找到 WeShirt 并展开。", "选择尺寸，点击「添加」或长按拖到主屏幕。")),
    GENERIC("Pixel / 原生 Android / 其他桌面", listOf("回到桌面，长按空白处。", "进入「小部件 / Widgets」，查找并展开 WeShirt。", "选择尺寸，长按拖到桌面空白处。")),
}

/** Public build/launcher information only; no device IDs or hidden system properties. */
internal fun detectWidgetDesktop(manufacturer: String, brand: String, launcher: String?): WidgetDesktopFamily {
    val home = launcher.orEmpty().lowercase(Locale.ROOT)
    when {
        home.startsWith("com.bbk.launcher") || home.startsWith("com.vivo.launcher") -> return WidgetDesktopFamily.ORIGIN
        home.startsWith("com.miui.home") || home.startsWith("com.mi.android.globallauncher") -> return WidgetDesktopFamily.XIAOMI
        home.startsWith("com.hihonor.android.launcher") -> return WidgetDesktopFamily.HONOR
        home.startsWith("com.huawei.android.launcher") -> return WidgetDesktopFamily.HUAWEI
        home.startsWith("com.sec.android.app.launcher") -> return WidgetDesktopFamily.SAMSUNG
        // A third-party home app can use a different menu from the phone brand.
        home.isNotEmpty() && home !in setOf("android", "com.android.launcher", "com.android.launcher3", "com.oppo.launcher", "com.coloros.launcher", "com.oneplus.launcher", "net.oneplus.launcher") -> return WidgetDesktopFamily.GENERIC
    }
    val device = (manufacturer + " " + brand).lowercase(Locale.ROOT)
    return when {
        "vivo" in device || "iqoo" in device -> WidgetDesktopFamily.ORIGIN
        "xiaomi" in device || "redmi" in device || "poco" in device -> WidgetDesktopFamily.XIAOMI
        "realme" in device -> WidgetDesktopFamily.REALME
        "oneplus" in device -> WidgetDesktopFamily.ONEPLUS
        "oppo" in device || home.contains("coloros") -> WidgetDesktopFamily.OPPO
        "honor" in device -> WidgetDesktopFamily.HONOR
        "huawei" in device -> WidgetDesktopFamily.HUAWEI
        "samsung" in device -> WidgetDesktopFamily.SAMSUNG
        else -> WidgetDesktopFamily.GENERIC
    }
}

internal data class WidgetEntryStatus(val key: String, val columns: Int, val rows: Int, val installed: Int) {
    val sizeLabel get() = "$columns × $rows"
    val description get() = when (columns to rows) {
        2 to 1 -> "紧凑卡片 · 洗衣进度与预计完成时间"
        2 to 2 -> "方形卡片 · 洗衣机与任务状态"
        else -> "横向卡片 · 更完整的机器与任务信息"
    }
}

internal data class WidgetsManagementState(
    val entries: List<WidgetEntryStatus>, val pinSupported: Boolean,
    val detected: WidgetDesktopFamily, val launcherName: String = "", val error: String? = null,
)
