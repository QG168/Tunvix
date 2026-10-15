package com.tunvix.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
class UiJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun channelToRemoteSwitchAndReturn() {
        compose.onNodeWithText("CCTV-1 综合").performClick()
        compose.onNodeWithText("频道列表").assertIsDisplayed()
        compose.onNodeWithText("遥控器").performClick()
        compose.onNodeWithText("下一台").assertIsDisplayed().performClick()
        compose.onNodeWithText("湖南卫视").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("直播源管理").assertIsDisplayed()
    }
    @Test fun sourceManagementAndManualChannelForm() {
        compose.onNodeWithContentDescription("直播源管理").performClick()
        compose.onNodeWithText("添加直播源").performClick()
        compose.onNodeWithText("手动添加频道").performClick()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("频道名称")).perform(androidx.test.espresso.action.ViewActions.replaceText("测试自建频道"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("播放链接")).perform(androidx.test.espresso.action.ViewActions.replaceText("https://example.com/test.m3u8"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        compose.onNodeWithText("保存频道").performClick()
        compose.onNodeWithText("测试自建频道").assertIsDisplayed()
    }
    @Test fun favoritesAndSettingsRemainReachable() {
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithText("我的收藏").assertIsDisplayed()
        compose.onNodeWithText("最近").performClick()
        compose.onNodeWithText("最近观看").assertIsDisplayed()
        compose.onNodeWithText("频道").performClick()
        compose.onNodeWithContentDescription("直播源管理").performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("移动网络播放提醒").assertIsDisplayed()
    }
}
