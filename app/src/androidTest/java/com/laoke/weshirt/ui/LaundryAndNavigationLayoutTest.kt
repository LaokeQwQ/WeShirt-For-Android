package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.LaundryTask
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaundryAndNavigationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun middleAssistantDestinationKeepsTheOriginalPageIdsAndRemainsVisibleOnNarrowScreens() {
        var selected = -1
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") { Box(Modifier.width(320.dp)) { WeShirtNavigation(AppState(tab = 4)) { selected = it } } }
            }
        }
        val laundry = compose.onNodeWithTag("navigation:1").assertIsDisplayed().getUnclippedBoundsInRoot()
        val assistant = compose.onNodeWithTag("navigation:4").assertIsDisplayed().assertIsSelected().getUnclippedBoundsInRoot()
        val channel = compose.onNodeWithTag("navigation:2").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("AI must sit between laundry and channels without overlap", laundry.right <= assistant.left && assistant.right <= channel.left)
        compose.onNodeWithTag("navigation:4").performClick()
        compose.runOnIdle { assertEquals(4, selected) }
        compose.onNodeWithTag("navigation:2").performClick()
        compose.runOnIdle { assertEquals("The channel must retain its original index", 2, selected) }
    }

    @Test fun taskCardLargeTextKeepsTakeAndPickupSeparateAndDeleteBehindAnExplicitMenu() {
        var taken = 0; var pickup = 0; var deleted = 0
        val task = LaundryTask("task", "machine", "一楼靠窗的长名称洗衣机", "washer", 0, false,
            "这是公开备注", true, true, false, completionConfirmed = true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WeShirtTheme("light", "blue") {
                    Box(Modifier.width(320.dp)) { LaundryTaskCard(task, null, 1_000, true, false, true, true, { taken++ }, { pickup++ }, { deleted++ }) }
                }
            }
        }
        compose.onNodeWithTag("laundry-task-time:task", useUnmergedTree = true).assertIsDisplayed()
        val take = compose.onNodeWithTag("laundry-take:task").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val friend = compose.onNodeWithTag("laundry-pickup:task").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val a = take.getUnclippedBoundsInRoot(); val b = friend.getUnclippedBoundsInRoot()
        assertTrue("Task actions must wrap into separate rows when necessary", a.right <= b.left || a.bottom <= b.top)
        take.performClick(); friend.performClick()
        compose.runOnIdle { assertEquals(1, taken); assertEquals(1, pickup); assertEquals(0, deleted) }
        compose.onNodeWithText("删除登记").assertDoesNotExist()
        capture("native-laundry-task-card")
        compose.onNodeWithTag("laundry-task-more:task").performClick()
        compose.onNodeWithText("删除登记").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    private fun capture(name: String) {
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { image.writeToTestStorage(name) } finally { image.recycle() }
    }
}
