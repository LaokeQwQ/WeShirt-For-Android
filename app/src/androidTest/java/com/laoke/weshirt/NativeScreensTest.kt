package com.laoke.weshirt

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.data.*
import com.laoke.weshirt.ui.MachineSheet
import com.laoke.weshirt.ui.RoomScreen
import com.laoke.weshirt.ui.WeShirtTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeScreensTest {
    @get:Rule val compose = createComposeRule()
    private fun machine(id: String, slot: String, kind: String = "washer") = Machine(id, "测试机 $slot", slot, kind, "idle", null, "", false, false, "")

    @Test fun nativeRoomLoadsAndSelectionPreservesBusinessId() {
        val machines = (1..7).map { machine("id-$it", "$it") } + listOf(machine("dry-1", "D1", "dryer"), machine("dry-2", "D2", "dryer"))
        var selection = ""
        var rendererFailure: String? = null
        compose.setContent { WeShirtTheme("light","blue") { RoomScreen(AppState(building="test", machines=machines), { selection = it }, { rendererFailure = it }) } }
        compose.waitForIdle()
        compose.onNodeWithText("测试机 D1", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("dry-1", selection); check(rendererFailure == null) { rendererFailure.orEmpty() } }
        compose.onNodeWithText("机器列表").performClick()
        compose.onNodeWithText("最快可用").assertIsDisplayed()
        compose.onNodeWithText("测试机 1").assertIsDisplayed()
        saveScreenshot("native-room-list.png")
    }

    @Test fun fortyMinuteDryingRequiresSecondConfirmation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val vm = AppViewModel(app)
        val machine = machine("dry-40", "D1", "dryer")
        val state = AppState(profile=Profile("test-account","测试用户","","test",1,"user"))
        compose.setContent { WeShirtTheme("light","blue") { MachineSheet(machine,state,vm,{}, {}) } }
        compose.onNodeWithText("登记我的烘干").performClick()
        compose.onNodeWithText("40").assertExists()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("确认登记", useUnmergedTree=true).assertExists()
        compose.onNodeWithText("备注、消毒液和自清洁信息将公开显示。").assertExists()
        compose.onNodeWithText("返回修改").performClick()
        compose.onNodeWithText("40").assertExists()
        saveScreenshot("drying-confirmation.png")
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        instrumentation.uiAutomation.takeScreenshot()?.let { image -> file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle() }
    }
}
