package com.tunvix.app.ui

import android.view.SurfaceView
import com.tunvix.app.services.*
import com.tunvix.contract.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowseExperienceTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun flush() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
    }
    private fun emptyModel(player: PlaybackController) = TunvixModel(AppServices(MemoryCatalog(),object:SourceImporter {
        override suspend fun inspect(request:ImportRequest)=Outcome.Success(ImportReport(emptyList()))
    },player,true))
    private fun playback() = object : PlaybackController {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        override fun attachSurface(surface: SurfaceView) {}; override fun detachSurface(surface: SurfaceView) {}
        override fun play(channel: Channel) {}; override fun pause() {}; override fun resume() {}
        override fun retry() {}; override fun stop() {}; override fun setVolume(percent: Int) {}
        override fun refreshVolume() {}; override fun onBackground() {}; override fun onForeground() {}; override fun release() {}
    }
    @Test fun homeRequestsViewportRatherThanWholeGroupAndCancelsOnPlayback() = runTest(dispatcher) {
        val channels = List(80) { Channel("c$it", "s", "频道$it", "https://example.com/$it", "体育") }
        val calls = mutableListOf<List<String>>()
        val repo = object : CatalogRepository by MemoryCatalog(CatalogSnapshot(
            sources = listOf(LiveSource("s", "测试", SourceKind.SUBSCRIPTION)), channels = channels, selectedSourceId = "s"
        )) {
            override suspend fun requestThumbnails(channelIds: List<String>, allowNetwork: Boolean): Outcome<Unit> {
                calls += channelIds; return Outcome.Success(Unit)
            }
        }
        val model = TunvixModel(AppServices(repo, object : SourceImporter {
            override suspend fun inspect(request: ImportRequest) = Outcome.Success(ImportReport(emptyList()))
        }, playback(), true))
        model.setVisibleChannels(listOf("c40", "c41", "c42", "c43"))
        runCurrent()
        assertEquals(listOf("c40", "c41", "c42", "c43"), calls.last())
        model.play(channels[40], true); flush()
        assertEquals(emptyList<String>(), calls.last())
    }
    @Test fun portraitControlsHideAndInteractionRestartsTimeout() = runTest(dispatcher) {
        val player=playback();val model=emptyModel(player)
        model.page=Page.PLAYER;player.snapshot.value=PlaybackSnapshot("c",PlaybackPhase.PLAYING);flush()
        advanceTimeBy(3000);model.showControls();flush()
        advanceTimeBy(1000);flush();assertTrue(model.controlsVisible)
        advanceTimeBy(2500);flush();assertFalse(model.controlsVisible)
    }
    @Test fun pauseBackgroundAndOverlayKeepControlsAvailable() = runTest(dispatcher) {
        val player=playback();val model=emptyModel(player)
        model.page=Page.PLAYER;player.snapshot.value=PlaybackSnapshot("c",PlaybackPhase.PLAYING);flush()
        advanceTimeBy(3500);flush();assertFalse(model.controlsVisible)
        player.snapshot.value=player.snapshot.value.copy(phase=PlaybackPhase.PAUSED);flush()
        advanceTimeBy(5000);flush();assertTrue(model.controlsVisible)
        player.snapshot.value=player.snapshot.value.copy(phase=PlaybackPhase.PLAYING);model.foreground=false;flush()
        advanceTimeBy(5000);flush();assertTrue(model.controlsVisible)
        model.foreground=true;model.overlay=Overlay.Appearance;flush()
        advanceTimeBy(5000);flush();assertTrue(model.controlsVisible)
        model.overlay=null;flush();advanceTimeBy(3500);flush();assertFalse(model.controlsVisible)
    }
    @Test fun fullscreenControlsUseSameTimerAndExitRestartsIt() = runTest(dispatcher) {
        val player=playback();val model=emptyModel(player)
        model.page=Page.PLAYER;player.snapshot.value=PlaybackSnapshot("c",PlaybackPhase.PLAYING);model.changeFullscreen(true);flush()
        advanceTimeBy(3500);flush();assertFalse(model.controlsVisible)
        model.changeFullscreen(false);flush();assertTrue(model.controlsVisible)
        advanceTimeBy(3500);flush();assertFalse(model.controlsVisible)
    }
    @Test fun playerPanelAndGroupInteractionsShowControlsAndRestartTimer() = runTest(dispatcher) {
        val player=playback();val model=emptyModel(player)
        model.page=Page.PLAYER;player.snapshot.value=PlaybackSnapshot("c",PlaybackPhase.PLAYING);flush()
        advanceTimeBy(3500);flush();assertFalse(model.controlsVisible)
        model.remote=true;flush();assertTrue(model.controlsVisible)
        advanceTimeBy(3000);model.playerGroup="体育";flush()
        advanceTimeBy(1000);flush();assertTrue(model.controlsVisible)
        advanceTimeBy(2500);flush();assertFalse(model.controlsVisible)
        model.changeFullscreen(true);flush();advanceTimeBy(3500);flush()
        model.back();flush();assertTrue(model.controlsVisible)
    }

    @Test fun sourceSwitchAndNewModelRestoreEachSourcesSavedGroup() = runTest(dispatcher) {
        val repo=MemoryCatalog(CatalogSnapshot(
            sources=listOf(LiveSource("a","甲",SourceKind.SUBSCRIPTION),LiveSource("b","乙",SourceKind.SUBSCRIPTION)),
            channels=listOf(Channel("a1","a","体育","https://p/a1","体育"),Channel("a2","a","电影","https://p/a2","电影"),
                Channel("b1","b","新闻","https://p/b1","新闻"),Channel("b2","b","纪录","https://p/b2","纪录")),
            selectedSourceId="a",settings=UserSettings(browseGroups=mapOf("a" to "电影","b" to "纪录"))
        ))
        fun make()=TunvixModel(AppServices(repo,object:SourceImporter {
            override suspend fun inspect(request:ImportRequest)=Outcome.Success(ImportReport(emptyList()))
        },playback(),true))
        val model=make();flush();assertEquals("电影",model.group)
        model.selectSource(model.catalog.sources[1]);flush();assertEquals("纪录",model.group)
        model.group="新闻";flush()
        assertEquals("新闻",repo.snapshot.value.settings.browseGroups["b"])
        model.selectSource(model.catalog.sources[0]);flush();assertEquals("电影",model.group)
        val restarted=make();flush();assertEquals("电影",restarted.group)
        restarted.selectSource(restarted.catalog.sources[1]);flush();assertEquals("新闻",restarted.group)
    }

}
