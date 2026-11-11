package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laoke.weshirt.data.InspectionIssue
import com.laoke.weshirt.data.Machine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePanelAndInspectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowShortLargeTextPanelKeepsPrimaryActionAndCloseVisibleWhileDetailsScroll() {
        var registered = 0
        var closed = 0
        val machine = Machine("washer", "一楼靠窗洗衣机 · 机器名称较长时也应完整提供操作入口", "1", "washer", "occupied", null, "", false, false, "")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") {
                    Box(Modifier.requiredSize(320.dp, 360.dp)) {
                        MachineSheetFrame(machine, false, true, { closed++ }, register = { registered++ }) {
                            repeat(14) { index -> Text("机器信息与现场说明 $index") }
                            Text("详情最后一行", Modifier.testTag("panel-last-detail"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("machine-sheet-close").assertIsDisplayed()
        compose.onNodeWithTag("machine-sheet-register").assertIsDisplayed().performClick()
        compose.onNodeWithTag("panel-last-detail").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("machine-sheet-register").assertIsDisplayed()
        compose.onNodeWithTag("machine-sheet-close").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, registered); assertEquals(1, closed) }
    }

    @Test fun selectingExceptionExposesMultipleReasonsAndUncheckingRestoresNormal() {
        var result: InspectionIssue? = null
        compose.setContent {
            var issue by remember { mutableStateOf<InspectionIssue?>(null) }
            result = issue
            WeShirtTheme("light", "blue") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InspectionMachineRow("一号洗衣机", issue, true, { issue = if (it) InspectionIssue("one") else null }, { issue = it })
                }
            }
        }
        compose.onNodeWithText("正常").assertIsDisplayed()
        compose.onNodeWithTag("inspection-reason:offline").assertDoesNotExist()
        compose.onNodeWithTag("inspection-machine-select").performClick().assertIsOn()
        compose.onNodeWithTag("inspection-reason:offline").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("inspection-reason:power_off").performScrollTo().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(setOf("offline", "power_off"), result?.reasons) }
        compose.onNodeWithTag("inspection-machine-select").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("inspection-reason:offline").assertDoesNotExist()
        compose.onNodeWithText("正常").assertIsDisplayed()
        compose.runOnIdle { assertNull(result) }
    }
}
