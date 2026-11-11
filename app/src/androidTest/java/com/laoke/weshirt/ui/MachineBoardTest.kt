package com.laoke.weshirt.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laoke.weshirt.data.Machine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MachineBoardTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_000_000L

    private fun machine(id: String, kind: String = "washer", state: String = "idle", suspected: Boolean = false) =
        Machine(id, "设备 $id", "1", kind, state, now + 120_000, "", false, false, "", suspectedUncollected = suspected)

    @Test fun defaultsToListAndFilteringLayoutAndSelectionPreserveBusinessId() {
        val machines = listOf(machine("wash"), machine("dry", "dryer"))
        var selected = ""
        compose.setContent { WeShirtTheme("light", "blue") { MachineBoard(machines, setOf("dry"), now, { selected = it.id }) } }
        compose.onNodeWithText("最快可用").assertIsDisplayed()
        compose.onNodeWithTag("machine-layout-list").assertIsOn()
        compose.onNodeWithTag("machine-layout-cards").assertIsOff()
        compose.onNodeWithTag("machine-filter-dryer").performClick().assertIsSelected()
        compose.onNodeWithText("洗衣机").assertDoesNotExist()
        compose.onNodeWithTag("machine:dry").assertIsDisplayed()
        compose.onNodeWithTag("machine-layout-cards").performClick().assertIsOn()
        compose.onNodeWithTag("machine-filter-dryer").assertIsSelected()
        compose.onNodeWithTag("machine:dry").performClick()
        compose.runOnIdle { assertEquals("dry", selected) }
        compose.onNodeWithTag("machine-filter-all").performClick()
        compose.onNodeWithTag("machine-filter-favorites").performClick().assertIsSelected()
        compose.onNodeWithText("洗衣机").assertDoesNotExist()
        compose.onNodeWithTag("machine:dry").assertIsDisplayed()
    }

    @Test fun suspiciousAndUnknownMachinesDoNotAdvertiseIdleOrCountdown() {
        val machines = listOf(machine("pickup", suspected = true), machine("unknown", state = "unknown"))
        compose.setContent { WeShirtTheme("light", "blue") { MachineBoard(machines, emptySet(), now, {}) } }
        compose.onNodeWithText("2 台 · 0 台空闲").assertIsDisplayed()
        listOf("pickup" to "疑似未取", "unknown" to "状态待更新").forEach { (id, status) ->
            val tag = "machine:$id"
            compose.onNodeWithTag("machine-board").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed().assertTextContains(status)
            // The building summary has its own "空闲" label; inspect each actual machine.
            compose.onNode(hasText("空闲") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertDoesNotExist()
            compose.onNode(hasText("预计剩余", substring = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
