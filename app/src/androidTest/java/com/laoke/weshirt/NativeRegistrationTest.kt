package com.laoke.weshirt

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.ui.RegisterDialog
import com.laoke.weshirt.ui.WeShirtTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRegistrationTest {
    @get:Rule val compose = createComposeRule()
    private fun machine(kind: String = "washer") = Machine("test-machine", "测试机器", "1", kind, "idle", null, "", false, false, "")

    @Test fun washerPresetsPublishTheChosenSelfCleaningProgramAndCareFlags() {
        var submitted = false
        compose.setContent { WeShirtTheme("light", "blue") {
            RegisterDialog(machine(), false, null, {}) { minutes, note, disinfectant, cleaned, followMachine, selfClean ->
                assertEquals(6, minutes); assertEquals("清洁完成后洗衣", note)
                assertTrue(disinfectant); assertTrue(cleaned); assertFalse(followMachine); assertTrue(selfClean)
                submitted = true
            }
        } }
        compose.onNodeWithText("桶自洁 6分钟").performClick()
        compose.onNodeWithText("公开备注（选填）").performScrollTo().performTextInput("清洁完成后洗衣")
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("提醒方式：桶自洁 · 6 分钟").assertExists()
        compose.onNodeWithText("消毒液：使用").assertExists()
        compose.onNodeWithText("洗衣前自清洁：已完成").assertExists()
        compose.onNode(hasText("确认登记") and hasClickAction()).performClick()
        compose.runOnIdle { assertTrue(submitted) }
    }

    @Test fun failedFortyMinuteDryingRetainsTheDraftAndShowsTraceableError() {
        var failure by mutableStateOf<String?>(null)
        var closed = false
        var submits = 0
        compose.setContent { WeShirtTheme("light", "blue") {
            RegisterDialog(machine("dryer"), false, failure, { closed = true }) { minutes, note, disinfectant, cleaned, _, selfClean ->
                assertEquals(40, minutes); assertEquals("烘干提醒", note)
                assertFalse(disinfectant); assertFalse(cleaned); assertFalse(selfClean)
                submits++; failure = "请求未完成\n状态码 503 · 事件 registration-test"
            }
        } }
        compose.onNodeWithText("使用消毒液").assertDoesNotExist()
        compose.onNodeWithText("洗衣前已完成自清洁").assertDoesNotExist()
        compose.onNodeWithText("公开备注（选填）").performTextInput("烘干提醒")
        compose.onNodeWithText("下一步").performClick()
        compose.onNode(hasText("确认登记") and hasClickAction()).performClick()
        compose.onNodeWithText("请求未完成\n状态码 503 · 事件 registration-test").assertExists()
        compose.runOnIdle { assertFalse(closed); assertEquals(1, submits) }
        compose.onNodeWithText("返回修改").performClick()
        compose.onNodeWithText("烘干提醒").assertExists()
        compose.onNodeWithText("40").assertExists()
    }

    @Test fun machineUpdatesRequireASecondReviewBeforeAnySubmission() {
        var current by mutableStateOf(machine())
        var submits = 0
        compose.setContent { WeShirtTheme("light", "blue") {
            RegisterDialog(current, false, null, {}) { _, _, _, _, _, _ -> submits++ }
        } }
        compose.onNodeWithText("下一步").performClick()
        compose.onNode(hasText("确认登记") and hasClickAction()).assertExists()
        compose.runOnIdle { current = current.copy(name = "更新后的测试机器", state = "occupied", finishAt = System.currentTimeMillis() + 60_000, estimated = true) }
        compose.onNodeWithText("机器信息已更新，请重新核对后确认登记。").assertExists()
        compose.onNodeWithText("下一步").assertExists()
        compose.runOnIdle { assertEquals(0, submits) }
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("更新后的测试机器").assertExists()
        compose.onNode(hasText("确认登记") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, submits) }
    }

    @Test fun correctedMachineKindClearsWasherProgramAndFlagsButRetainsPublicNote() {
        var current by mutableStateOf(machine())
        var submits = 0
        compose.setContent { WeShirtTheme("light", "blue") {
            RegisterDialog(current, false, null, {}) { minutes, note, disinfectant, cleaned, followMachine, selfClean ->
                assertEquals(40, minutes); assertEquals("仍保留这条备注", note)
                assertFalse(disinfectant); assertFalse(cleaned); assertFalse(followMachine); assertFalse(selfClean)
                submits++
            }
        } }
        compose.onNodeWithText("桶自洁 6分钟").performClick()
        compose.onNodeWithText("公开备注（选填）").performScrollTo().performTextInput("仍保留这条备注")
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("提醒方式：桶自洁 · 6 分钟").assertExists()
        compose.runOnIdle { current = current.copy(kind = "dryer") }
        compose.onNodeWithText("机器信息已更新，请重新核对后确认登记。").assertExists()
        compose.onNodeWithText("40").assertExists()
        compose.onNodeWithText("仍保留这条备注").assertExists()
        compose.onNodeWithText("使用消毒液").assertDoesNotExist()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("提醒方式：桶自洁 · 6 分钟").assertDoesNotExist()
        compose.onNode(hasText("确认登记") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, submits) }
    }
}
