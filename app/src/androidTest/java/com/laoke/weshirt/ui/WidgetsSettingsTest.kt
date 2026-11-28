package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.platform.widget.LaundryWidgetRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetsSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val fixtures = listOf(WidgetEntryStatus("mini", 2, 1, 2), WidgetEntryStatus("square", 2, 2, 0), WidgetEntryStatus("wide", 4, 2, 1))

    @Test fun paletteDropdownShowsCurrentSelectionAndAppliesChosenColor() {
        compose.setContent {
            var palette by remember { mutableStateOf("blue") }
            WeShirtTheme("light", palette) {
                Box(Modifier.width(320.dp)) { PalettePicker("light", palette) { palette = it } }
            }
        }
        compose.onNodeWithTag("palette-dropdown").assertHeightIsAtLeast(48.dp).assertTextContains("蓝色").performClick()
        compose.onNodeWithText("紫色").assertIsDisplayed().performClick()
        compose.onNodeWithTag("palette-dropdown").assertTextContains("紫色")
        compose.onNodeWithText("绿色").assertDoesNotExist()
    }

    @Test fun compactLargeTextManagementPreservesSizeCountsAndExactPinChoice() {
        var pinned = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        WidgetsSettingsContent(WidgetsManagementState(fixtures, true, WidgetDesktopFamily.ORIGIN), onAdd = { pinned = it.key }, onHome = {})
                    }
                }
            }
        }
        compose.onNodeWithText("已添加 2 个").assertIsDisplayed()
        compose.onNodeWithTag("widgets-management").performScrollToNode(hasTestTag("widget-add:2x1"))
        compose.onNodeWithTag("widget-add:2x1").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("mini", pinned) }
        compose.onNodeWithTag("widgets-management").performScrollToNode(hasTestTag("widget-add:2x2"))
        compose.onNodeWithTag("widget-add:2x2").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("square", pinned) }
        compose.onNodeWithTag("widgets-management").performScrollToNode(hasTestTag("widget-add:4x2"))
        compose.onNodeWithTag("widget-add:4x2").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("wide", pinned) }
    }

    @Test fun unsupportedPinUsesManualGuideAndCanSwitchToAnotherDesktop() {
        compose.setContent { WeShirtTheme("dark", "green") {
            WidgetsSettingsContent(WidgetsManagementState(fixtures, false, WidgetDesktopFamily.ORIGIN), onAdd = { fail("Unsupported pin cannot invoke a request") }, onHome = {})
        } }
        compose.onNodeWithTag("widget-add:2x1").assertIsNotEnabled()
        compose.onNodeWithTag("widgets-management").performScrollToNode(hasTestTag("widget-guide-family"))
        compose.onNodeWithTag("widget-guide-family").performClick()
        compose.onNodeWithTag("widget-guide-option:HUAWEI").performScrollTo().performClick()
        compose.onNodeWithTag("widgets-management").performScrollToNode(hasTestTag("widget-guide-step:1"))
        compose.onNodeWithTag("widget-guide-step:1").assertTextContains("窗口小工具", substring = true)
    }

    @Test fun systemCountsAndPinCatalogueMatchTheThreeActualProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = AndroidWidgetsManager(context).read()
        assertEquals(setOf(2 to 1, 2 to 2, 4 to 2), state.entries.map { it.columns to it.rows }.toSet())
        assertEquals(LaundryWidgetRegistry.entries.map { it.providerClass.name }.toSet(), state.entries.map { it.key }.toSet())
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        state.entries.forEach { entry ->
            val provider = LaundryWidgetRegistry.entries.single { it.providerClass.name == entry.key }
            assertEquals(manager.getAppWidgetIds(android.content.ComponentName(context, provider.providerClass)).size, entry.installed)
        }
        assertFalse("Arbitrary components cannot be pinned by the management UI", AndroidWidgetsManager(context).add("unknown.component"))
    }
}
