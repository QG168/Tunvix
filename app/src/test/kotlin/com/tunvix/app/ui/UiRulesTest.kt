package com.tunvix.app.ui
import com.tunvix.contract.*
import org.junit.Assert.*
import org.junit.Test
class UiRulesTest {
    private fun channel(id: String, source: String = "a", group: String = "新闻") = Channel(id, source, "同名频道", "https://example.com/$id", group)
    @Test fun screenStaysAwakeOnlyDuringForegroundVideoPlayback() {
        assertTrue(UiRules.keepScreenAwake(Page.PLAYER,PlaybackPhase.PLAYING,true,false))
        for(phase in PlaybackPhase.entries.filter{it!=PlaybackPhase.PLAYING})
            assertFalse(UiRules.keepScreenAwake(Page.PLAYER,phase,true,false))
        for(page in Page.entries.filter{it!=Page.PLAYER})
            assertFalse(UiRules.keepScreenAwake(page,PlaybackPhase.PLAYING,true,false))
        assertFalse(UiRules.keepScreenAwake(Page.PLAYER,PlaybackPhase.PLAYING,false,false))
        assertFalse(UiRules.keepScreenAwake(Page.PLAYER,PlaybackPhase.PLAYING,true,true))
    }
    @Test fun switchingWrapsOnlyWithinCurrentSourceAndGroup() {
        val a = channel("1"); val b = channel("2")
        val all = listOf(a, channel("foreign", "b"), channel("sports", group = "体育"), b)
        assertEquals(a, UiRules.nextChannel(all, b, 1))
        assertEquals(b, UiRules.nextChannel(all, a, -1))
        assertEquals(b, UiRules.nextChannel(all, a, 1))
    }
    @Test fun singleChannelRemainsSelected() {
        val a = channel("1")
        assertEquals(a, UiRules.nextChannel(listOf(a), a, 1))
    }
    @Test fun searchUsesSelectedSourceWithoutChangingStoredOrder() {
        val all = listOf(channel("1"), channel("foreign", "b"), channel("2", group = "体育"))
        val state = CatalogSnapshot(channels = all, selectedSourceId = "a")
        assertEquals(listOf("1", "2"), UiRules.visibleChannels(state, "新闻", "同名").map { it.id })
        assertEquals(listOf("1"), UiRules.visibleChannels(state, "新闻", "").map { it.id })
        assertEquals(all, state.channels)
    }
    @Test fun formRejectsNonHttpAndMalformedAddresses() {
        assertTrue(UiRules.validHttpAddress("https://example.com/live?token=123"))
        assertTrue(UiRules.validHttpAddress("http://192.168.1.1/tv.php"))
        listOf("", "https://", "file:///private/test", "javascript:alert(1)", "https://host name/a").forEach { assertFalse(it, UiRules.validHttpAddress(it)) }
    }
}

class FirstFrameTierTest {
    private fun ok(ms: Long) = FirstFrameResult(FirstFrameStatus.SUCCESS, ms, 0)
    @Test fun tierBoundariesUseRawMilliseconds() {
        // 1000/1001/3000/3001 ms 边界：1000 属绿、3000 属黄，不得先四舍五入成秒。
        assertEquals(FrameTier.FAST, UiRules.firstFrameTier(ok(1000)))
        assertEquals(FrameTier.MEDIUM, UiRules.firstFrameTier(ok(1001)))
        assertEquals(FrameTier.MEDIUM, UiRules.firstFrameTier(ok(3000)))
        assertEquals(FrameTier.SLOW, UiRules.firstFrameTier(ok(3001)))
        assertEquals(FrameTier.FAST, UiRules.firstFrameTier(ok(100)))
        assertEquals(FrameTier.SLOW, UiRules.firstFrameTier(ok(4200)))
    }
    @Test fun taskOutcomesMapToTimeoutAndFailureNotZero() {
        assertEquals(FrameTier.TIMEOUT, UiRules.firstFrameTier(FirstFrameResult(FirstFrameStatus.TIMEOUT, null, 0)))
        assertEquals(FrameTier.FAILED, UiRules.firstFrameTier(FirstFrameResult(FirstFrameStatus.FAILED, null, 0)))
        // 不能仅因数值变红就标为失败：慢但仍成功的结果保持 SLOW。
        assertEquals(FrameTier.SLOW, UiRules.firstFrameTier(ok(9000)))
    }
    @Test fun pendingStatesStayGray() {
        assertEquals(FrameTier.PENDING, UiRules.firstFrameTier(null, false))
        assertEquals(FrameTier.PENDING, UiRules.firstFrameTier(null, true))
        assertEquals(FrameTier.PENDING, UiRules.firstFrameTier(ok(860), true))
        assertEquals(FrameTier.FAST, UiRules.firstFrameTier(ok(860), false))
    }
    @Test fun labelsNeverShowFailureAsZero() {
        assertEquals("860 ms", UiRules.firstFrameLabel(ok(860)))
        assertEquals("超时", UiRules.firstFrameLabel(FirstFrameResult(FirstFrameStatus.TIMEOUT, null, 0)))
        assertEquals("失败", UiRules.firstFrameLabel(FirstFrameResult(FirstFrameStatus.FAILED, null, 0)))
        assertEquals("…", UiRules.firstFrameLabel(null, true))
        assertEquals("—", UiRules.firstFrameLabel(null, false))
        assertEquals("—", UiRules.firstFrameLabel(FirstFrameResult(FirstFrameStatus.SUCCESS, null, 0)))
    }
}
