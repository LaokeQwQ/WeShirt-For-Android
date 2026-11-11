package com.laoke.weshirt.room

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Machine
import com.laoke.weshirt.ui.RoomScreen
import com.laoke.weshirt.ui.WeShirtTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the UI host's real native-renderer failure callback and retry lifecycle. */
@RunWith(AndroidJUnit4::class)
class NativeRoomFallbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun isolateBackend() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("preferences", 0)
            .edit().clear().putString("endpoint", "").commit()
    }

    @Test fun unavailableRoomFallsBackToUsableListWithoutStartupErrorAndCanRetry() {
        val machine = Machine("fallback-machine", "回退测试机", "1", "washer", "idle", null, "", false, false, "")
        var selected = ""
        val notices = mutableListOf<String>()
        compose.setContent {
            WeShirtTheme("light", "blue") {
                RoomScreen(AppState(building = "test", machines = listOf(machine)), { selected = it.id }, notices::add)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("已切换机器列表").assertDoesNotExist()
        lateinit var failedRoom: LaundryRoomView
        compose.runOnIdle {
            failedRoom = requireNotNull(findRoom(compose.activity.window.decorView))
            failedRoom.onRendererError?.invoke("ROOM_RENDERER_UNAVAILABLE · 状态 503 · 事件 test-fallback")
        }
        compose.onNodeWithText("最快可用").assertIsDisplayed()
        compose.onNodeWithText("已切换机器列表").assertIsDisplayed()
        compose.onNodeWithText("回退测试机").performClick()
        compose.runOnIdle {
            assertEquals(machine.id, selected)
            assertTrue("Renderer recovery must not display a startup error snackbar", notices.isEmpty())
            assertTrue("The failed native view must release its GPU resources", failedRoom.renderStatistics.disposed)
        }
        compose.onNodeWithText("重试 3D").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertNotSame(failedRoom, requireNotNull(findRoom(compose.activity.window.decorView))) }
        compose.onNodeWithText("已切换机器列表").assertDoesNotExist()
    }

    @Test fun aBuildingWithoutASceneStartsInTheListWithoutCreatingARenderer() {
        val machine = Machine("list-machine", "列表测试机", "1", "washer", "idle", null, "", false, false, "")
        val notices = mutableListOf<String>()
        compose.setContent {
            WeShirtTheme("light", "blue") {
                RoomScreen(AppState(building = "unmapped-building", machines = listOf(machine)), {}, notices::add, allow3D = false)
            }
        }
        compose.onNodeWithText("最快可用").assertIsDisplayed()
        compose.onNodeWithText("列表测试机").assertIsDisplayed()
        compose.onNodeWithText("3D 洗衣房").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(findRoom(compose.activity.window.decorView))
            assertTrue("Missing optional scenery is not a startup error", notices.isEmpty())
        }
    }

    private fun findRoom(view: View): LaundryRoomView? {
        if (view is LaundryRoomView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findRoom(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
