package com.tunvix.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ExperienceUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun selectedGroupCentresOnEntrySelectionAndReturnWithoutBlockingDrag() {
        val groups=List(35){"频道分组 $it"}
        var selected by mutableStateOf(groups[20])
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    if(visible)CenteredGroups(groups,selected,"source",Modifier.fillMaxWidth().testTag("groups")) { selected=it }
                    Button(onClick={visible=!visible}) { Text("切换页面") }
                    Button(onClick={selected=groups[25]}) { Text("选择25") }
                }
            }
        }
        assertCentred(groups[20])
        compose.onNodeWithTag("groups").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(groups[20],selected)
        compose.onNodeWithText("切换页面").performClick()
        compose.onNodeWithText("切换页面").performClick()
        assertCentred(groups[20])
        compose.onNodeWithText("选择25").performClick()
        assertCentred(groups[25])
    }
    @Test fun compactGroupsKeepLastSelectedVisibleAtTheBoundary() {
        val groups=List(25){"分组 $it"}
        compose.setContent { MaterialTheme { CenteredGroups(groups,groups.last(),"player",Modifier.fillMaxWidth().testTag("groups"),true) {} } }
        compose.onNodeWithText(groups.last()).assertIsDisplayed()
        val row=compose.onNodeWithTag("groups").getUnclippedBoundsInRoot()
        val chip=compose.onNodeWithText(groups.last()).getUnclippedBoundsInRoot()
        assertTrue(chip.right<=row.right)
    }
    @Test fun nativeInputUpdatesThemeWithoutResettingTextOrFocus() {
        var dark by mutableStateOf(false)
        var value by mutableStateOf("中文地址 https://example.com")
        compose.setContent {
            CompositionLocalProvider(LocalDesign provides DesignPalette(dark)) {
                MaterialTheme {
                    Column { Field("测试输入",value,"输入",onChange={value=it});Button(onClick={dark=!dark}) { Text("切换主题") } }
                }
            }
        }
        onView(withContentDescription("测试输入")).perform(click(),replaceText("保留内容"),closeSoftKeyboard())
        compose.onNodeWithText("切换主题").performClick()
        onView(withContentDescription("测试输入")).check { view, error ->
            if(error!=null)throw error
            val edit=view as android.widget.EditText
            assertEquals("保留内容",edit.text.toString())
            assertEquals(DesignPalette(true).ink.toArgb(),edit.currentTextColor)
        }
    }
    private fun assertCentred(group:String) {
        compose.waitUntil(5000) {
            val node=compose.onAllNodesWithText(group).fetchSemanticsNodes().firstOrNull() ?: return@waitUntil false
            val row=compose.onNodeWithTag("groups").fetchSemanticsNode().boundsInRoot
            abs(node.boundsInRoot.center.x-row.center.x)<8
        }
        compose.onNodeWithText(group).assertIsDisplayed()
    }
}
