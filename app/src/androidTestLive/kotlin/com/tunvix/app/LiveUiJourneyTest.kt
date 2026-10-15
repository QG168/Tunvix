package com.tunvix.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class LiveUiJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun importBrowseDecodeRemoteFullscreenAndDelete() {
        val context = InstrumentationRegistry.getInstrumentation().context
        FixtureServer(context.assets.open("test-video.mp4").use { it.readBytes() }).use { server ->
            val sourceName = "验收-${UUID.randomUUID().toString().take(6)}"
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("直播源管理").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("直播源管理").performClick()
            compose.onNodeWithText("添加直播源").performClick()
            compose.onNodeWithText("订阅链接").performClick()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("直播源名称")).perform(androidx.test.espresso.action.ViewActions.replaceText(sourceName), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("订阅链接")).perform(androidx.test.espresso.action.ViewActions.replaceText("${server.base}/list.m3u"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
            compose.onNodeWithText("解析订阅").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("确认导入").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("确认导入").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("测试画面").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("测试画面").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("正在连接").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("暂时无法播放").assertDoesNotExist()
            compose.onNodeWithText("频道列表").assertIsDisplayed()
            compose.onNodeWithText("遥控器").performClick()
            compose.onNodeWithText("下一台").assertIsDisplayed()
            compose.onNodeWithContentDescription("全屏").performClick()
            compose.onNodeWithContentDescription("退出全屏").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("直播源管理").performClick()
            compose.onNodeWithContentDescription("删除 $sourceName").performClick()
            compose.onNodeWithText("删除直播源").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(sourceName).fetchSemanticsNodes().isEmpty() }
            assert(server.requests.any { it["path"] == "/stream" && it["referer"] == "${server.base}/ref" })
        }
    }
}
