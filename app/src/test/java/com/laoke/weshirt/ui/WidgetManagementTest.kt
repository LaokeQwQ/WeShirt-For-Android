package com.laoke.weshirt.ui

import org.junit.Assert.*
import org.junit.Test

class WidgetManagementTest {
    @Test fun actualLauncherTakesPrecedenceOverPhoneManufacturer() {
        assertEquals(WidgetDesktopFamily.GENERIC, detectWidgetDesktop("vivo", "iQOO", "com.teslacoilsw.launcher"))
        assertEquals(WidgetDesktopFamily.XIAOMI, detectWidgetDesktop("unknown", "unknown", "com.miui.home"))
        assertEquals(WidgetDesktopFamily.HONOR, detectWidgetDesktop("HUAWEI", "HONOR", "com.hihonor.android.launcher"))
    }

    @Test fun missingLauncherInformationUsesKnownBrandAndUnknownFallsBackToAndroid() {
        val cases = mapOf(
            "vivo" to WidgetDesktopFamily.ORIGIN, "iQOO" to WidgetDesktopFamily.ORIGIN,
            "POCO" to WidgetDesktopFamily.XIAOMI, "OPPO" to WidgetDesktopFamily.OPPO,
            "realme" to WidgetDesktopFamily.REALME, "OnePlus" to WidgetDesktopFamily.ONEPLUS,
            "HUAWEI" to WidgetDesktopFamily.HUAWEI, "HONOR" to WidgetDesktopFamily.HONOR,
            "samsung" to WidgetDesktopFamily.SAMSUNG, "Google" to WidgetDesktopFamily.GENERIC,
        )
        cases.forEach { (brand, expected) -> assertEquals(brand, expected, detectWidgetDesktop(brand, brand, null)) }
        assertEquals(WidgetDesktopFamily.GENERIC, detectWidgetDesktop("unknown", "unknown", null))
    }
}
