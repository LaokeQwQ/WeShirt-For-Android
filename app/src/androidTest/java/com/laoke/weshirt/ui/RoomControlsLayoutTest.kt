package com.laoke.weshirt.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.room.RoomMachine
import com.laoke.weshirt.room.RoomMachineState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomControlsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Before fun isolateBackend() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("preferences", 0)
            .edit().clear().putString("endpoint", "").commit()
    }

    @Test fun narrowLargeTextModeControlsAndSlotShortcutsKeepSelectionAndBusinessIdentity() {
        var chosen = ""
        val slots = listOf(
            RoomMachine("dryer-business-id", "D1", "一楼靠窗烘干机，完整名称仅用于辅助识别", RoomMachineState.RUNNING, kind = "dryer"),
            RoomMachine("washer-business-id", "1", "一楼第一台洗衣机", RoomMachineState.IDLE),
            RoomMachine("unbound:2", "2", "未绑定机位", RoomMachineState.UNKNOWN)
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") {
                    Column(Modifier.width(320.dp).testTag("narrow-room-controls")) {
                        var room by remember { mutableStateOf(true) }
                        var selected by remember { mutableStateOf<String?>(null) }
                        RoomViewModeSwitch(room, true, { room = true }, { room = false })
                        RoomMachineShortcuts(slots, selected, false, {}, { chosen = it.id; selected = it.id }, {})
                    }
                }
            }
        }
        val roomMode = compose.onNodeWithTag("room-mode-3d").assertIsDisplayed()
        val listMode = compose.onNodeWithTag("room-mode-list").assertIsDisplayed()
        val roomBounds = roomMode.getUnclippedBoundsInRoot()
        val listBounds = listMode.getUnclippedBoundsInRoot()
        assertTrue("The two modes must not overlap", roomBounds.right <= listBounds.left + 2.dp)
        roomMode.assertHeightIsAtLeast(48.dp)
        listMode.assertHeightIsAtLeast(48.dp)
        listMode.performClick().assertIsSelected()
        roomMode.performClick().assertIsSelected()
        compose.onNodeWithTag("room-slot:D1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("dryer-business-id", chosen) }
        compose.onNodeWithTag("room-slot:2").assertIsNotEnabled()
        compose.onNodeWithTag("room-slot:1").performClick()
        compose.runOnIdle { assertEquals("washer-business-id", chosen) }
        compose.onNodeWithTag("room-favorites").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val common = compose.onNodeWithTag("room-favorites").getUnclippedBoundsInRoot()
        val facility = compose.onNodeWithTag("room-facilities").getUnclippedBoundsInRoot()
        assertTrue("Common machines and information must not overlap", common.right <= facility.left || common.bottom <= facility.top)
        saveScreenshot("native-narrow-shortcuts")
    }

    @Test fun narrowLargeTextMachineListControlsStaySeparateAndMachineOpensCorrectly() {
        var chosen = ""
        val machine = Machine("long-name-machine", "第一层洗衣房靠窗侧一号洗衣机 · 长名称", "1", "washer", "idle", null, "请查看公开备注", true, true, "")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { MachineBoard(listOf(machine), setOf(machine.id), 0, { chosen = it.id }) }
                }
            }
        }
        compose.onNodeWithTag("machine-board").performScrollToNode(hasTestTag("machine-layout-list"))
        val list = compose.onNodeWithTag("machine-layout-list").assertIsDisplayed()
        val cards = compose.onNodeWithTag("machine-layout-cards").assertIsDisplayed()
        assertTrue("View toggles must stay beside each other", list.getUnclippedBoundsInRoot().right <= cards.getUnclippedBoundsInRoot().left)
        cards.performClick().assertIsOn()
        list.performClick().assertIsOn()
        compose.onNodeWithTag("machine-board").performScrollToNode(hasTestTag("machine-filter-dryer"))
        compose.onNodeWithTag("machine-filter-dryer").performClick().assertIsSelected()
        compose.onNodeWithTag("machine:long-name-machine").assertDoesNotExist()
        compose.onNodeWithTag("machine-filter-favorites").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val dryerFilter = compose.onNodeWithTag("machine-filter-dryer").getUnclippedBoundsInRoot()
        val favoriteFilter = compose.onNodeWithTag("machine-filter-favorites").getUnclippedBoundsInRoot()
        assertEquals(dryerFilter.top, favoriteFilter.top)
        assertTrue("Two-column filters must have separate touch bounds", dryerFilter.right <= favoriteFilter.left)
        compose.onNodeWithTag("machine-filter-favorites").performClick().assertIsSelected()
        compose.onNodeWithTag("machine-filter-washer").performClick().assertIsSelected()
        compose.onNodeWithTag("machine-board").performScrollToNode(hasTestTag("machine:long-name-machine"))
        val card = compose.onNodeWithTag("machine:long-name-machine").assertIsDisplayed()
        val bounds = card.getUnclippedBoundsInRoot()
        val slot = compose.onNodeWithTag("machine-slot:long-name-machine", useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag("machine-name:long-name-machine", useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val favorite = compose.onNodeWithTag("machine-favorite:long-name-machine", useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The physical number, two-line name and favorite must have separate columns", slot.right <= name.left && name.right <= favorite.left)
        assertTrue("A long name and favorite must stay inside the narrow row", name.right <= bounds.right && favorite.right <= bounds.right)
        compose.onNode(hasText("1") and hasAnyAncestor(hasTestTag("machine-slot:long-name-machine")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("machine-type:long-name-machine", useUnmergedTree = true).assertTextContains("洗衣")
        card.performClick()
        compose.runOnIdle { assertEquals("long-name-machine", chosen) }
        saveScreenshot("native-narrow-machine-list")
    }

    @Test fun settingsHideServiceAddressAndKeepPerformanceReachable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vm = AppViewModel(app)
        compose.setContent { WeShirtTheme("light", "blue") { SettingsScreen(AppState(), vm, {}, {}, {}) } }
        compose.onNodeWithTag("settings-service-address").assertDoesNotExist()
        compose.onNodeWithText("服务地址设置").assertDoesNotExist()
        compose.onNodeWithTag("settings-sections").performScrollToNode(hasText("显示与性能"))
        compose.onNodeWithText("显示与性能").assertIsDisplayed()
        saveScreenshot("native-settings-performance")
    }

    @Test fun shortLargeTextRoomRetainsActualSceneAndReachableShortcuts() {
        var chosen = ""
        val machine = Machine("native-short-room", "一楼长名称洗衣机", "1", "washer", "idle", null, "", false, false, "")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") {
                    Box(Modifier.requiredSize(320.dp, 360.dp)) { RoomScreen(AppState(building = "test", machines = listOf(machine)), { chosen = it.id }, {}) }
                }
            }
        }
        compose.onNodeWithTag("native-room-scene").assertIsDisplayed().assertHeightIsAtLeast(180.dp)
        compose.onNodeWithTag("room-machine-shortcuts").assertIsDisplayed()
        compose.onNodeWithTag("room-slot:1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("native-short-room", chosen) }
        android.os.SystemClock.sleep(2000)
        saveScreenshot("native-short-room-controls")
    }

    private fun saveScreenshot(name: String) {
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { image.writeToTestStorage(name) } finally { image.recycle() }
    }
}
