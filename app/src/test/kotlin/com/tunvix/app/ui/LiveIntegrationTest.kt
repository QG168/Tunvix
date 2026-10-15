package com.tunvix.app.ui

import android.view.SurfaceView
import androidx.lifecycle.ViewModelStore
import com.tunvix.app.services.*
import com.tunvix.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    // JVM tests have no Compose owner to flush global snapshot notifications.
    private fun flush() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
    }
    private class Fixture {
        val ready = MutableStateFlow(true)
        val error = MutableStateFlow<String?>(null)
        val thumbnailRequests = mutableListOf<Pair<List<String>, Boolean>>()
        val calls = mutableListOf<String>()
        val refreshGate = CompletableDeferred<Unit>()
        val screenshotGate = CompletableDeferred<Unit>()
        var refreshResult: Outcome<Unit> = Outcome.Success(Unit)
        val screenshotBatches=mutableListOf<List<String>>()
        val channel = Channel("c", "custom", "Test", "https://p/live",group="央视频道")
        val repository = object : CatalogRepository by MemoryCatalog(CatalogSnapshot(
            sources = listOf(LiveSource("custom", "自建频道", SourceKind.CUSTOM)),
            channels = listOf(channel), selectedSourceId = "custom",
        )) {
            override suspend fun refreshSource(id: String): Outcome<Unit> { refreshGate.await(); return refreshResult }
            override suspend fun refreshThumbnails(channelIds: List<String>): Outcome<Unit> { screenshotBatches+=channelIds; screenshotGate.await(); return Outcome.Success(Unit) }
            override suspend fun requestThumbnails(channelIds: List<String>, allowNetwork: Boolean): Outcome<Unit> {
                thumbnailRequests += channelIds to allowNetwork
                return Outcome.Success(Unit)
            }
        }
        val player = object : PlaybackController {
            override val snapshot = MutableStateFlow(PlaybackSnapshot())
            override fun play(channel: Channel) { calls += "play"; snapshot.value = PlaybackSnapshot(channelId = channel.id, phase = PlaybackPhase.CONNECTING) }
            override fun attachSurface(surface: SurfaceView) {}; override fun detachSurface(surface: SurfaceView) {}
            override fun pause() {}; override fun resume() {}; override fun retry() {}; override fun stop() { calls += "stop" }
            override fun setVolume(percent: Int) {}; override fun refreshVolume() {}
            override fun onBackground() {}; override fun onForeground() {}; override fun release() { calls += "release" }
        }
        fun model() = TunvixModel(AppServices(repository,
            object : SourceImporter { override suspend fun inspect(request: ImportRequest): Outcome<ImportReport> = Outcome.Failure("unused") },
            player, false, true, ready, error, { calls += "closeCatalog" },
        ))
    }
    @Test fun readinessAndStorageFailureAreObserved() = runTest(dispatcher) {
        val f = Fixture(); f.ready.value = false
        val m = f.model(); flush(); assertFalse(m.catalogReady)
        f.ready.value = true; f.error.value = "读取失败"; flush()
        assertTrue(m.catalogReady); assertEquals("读取失败", m.catalogError)
    }
    @Test fun loadingPreventsPlaybackFromEmptyStartupState() = runTest(dispatcher) {
        val f = Fixture(); f.ready.value = false
        val m = f.model(); flush(); m.play(f.channel); flush()
        assertFalse(f.calls.contains("play"))
        assertEquals(Page.HOME, m.page)
    }
    @Test fun playbackAndBackgroundCancelThumbnailRequests() = runTest(dispatcher) {
        val f = Fixture(); val m = f.model(); flush()
        m.setVisibleChannels(listOf("c")); flush()
        assertEquals(listOf("c"), f.thumbnailRequests.last().first)
        m.play(f.channel); flush()
        assertEquals(emptyList<String>(), f.thumbnailRequests.last().first)
        m.back(); m.setVisibleChannels(listOf("c")); flush()
        m.foreground = false; flush()
        assertEquals(emptyList<String>(), f.thumbnailRequests.last().first)
    }
    @Test fun mobileNetworkRequestsCurrentGroupScreenshots() = runTest(dispatcher) {
        val f = Fixture(); val m = f.model(); flush()
        m.mobileNetwork = true; m.setVisibleChannels(listOf("c")); flush()
        assertTrue(f.thumbnailRequests.last().second)
    }
    @Test fun refreshFinishingInBackgroundDoesNotRestartPlayback() = runTest(dispatcher) {
        val f = Fixture(); val m = f.model(); flush(); m.play(f.channel); flush()
        m.refreshPlayingSource(); flush(); m.foreground = false
        f.refreshGate.complete(Unit); flush()
        assertEquals(1, f.calls.count { it == "play" })
        m.onForeground(); flush()
        assertEquals(2, f.calls.count { it == "play" })
    }
    @Test fun subscriptionRefreshHasBusyAndSuccessFeedback() = runTest(dispatcher) {
        val f=Fixture(); val m=f.model(); flush()
        val source=m.catalog.sources.single()
        m.refreshSubscription(source);flush()
        assertTrue(source.id in m.refreshingSourceIds)
        m.refreshSubscription(source);flush()
        f.refreshGate.complete(Unit);flush()
        assertTrue(m.refreshingSourceIds.isEmpty())
        assertEquals("自建频道：订阅已更新",m.toast)
    }
    @Test fun subscriptionRefreshFailureUsesSmallMessageAndStopsAnimation() = runTest(dispatcher) {
        val f=Fixture();f.refreshResult=Outcome.Failure("超时")
        val m=f.model();flush();m.refreshSubscription(m.catalog.sources.single());flush()
        f.refreshGate.complete(Unit);flush()
        assertTrue(m.refreshingSourceIds.isEmpty());assertEquals("刷新失败：超时",m.toast)
        assertNull(m.overlay)
    }
    @Test fun screenshotRefreshStopsWhenEnteringPlayback() = runTest(dispatcher) {
        val f=Fixture();val m=f.model();flush()
        m.refreshGroupScreenshots();flush()
        assertTrue(m.screenshotRefreshing);assertEquals(listOf(listOf("c")),f.screenshotBatches)
        m.play(f.channel);flush()
        assertFalse(m.screenshotRefreshing)
        assertEquals(Page.PLAYER,m.page)
    }
    @Test fun viewportScreenshotsFollowSearchAndAllowMobileNetwork() = runTest(dispatcher) {
        val f=Fixture();val m=f.model();flush()
        m.mobileNetwork=true;m.setVisibleChannels(listOf("c"));flush()
        assertEquals(listOf("c"),f.thumbnailRequests.last().first)
        assertTrue(f.thumbnailRequests.last().second)
        m.query="不存在";m.setVisibleChannels(emptyList());flush()
        assertEquals(emptyList<String>(),f.thumbnailRequests.last().first)
        // Explicit refresh retains the whole-group behavior even with no search matches.
        m.refreshGroupScreenshots();flush()
        assertEquals(listOf("c"),f.screenshotBatches.last())
        f.screenshotGate.complete(Unit);flush()
    }
    @Test fun viewModelCleanupClosesCatalogAndPlayer() = runTest(dispatcher) {
        val f = Fixture(); val m = f.model(); val store = ViewModelStore()
        store.put("test", m); store.clear()
        assertTrue(f.calls.contains("release")); assertTrue(f.calls.contains("closeCatalog"))
    }
}
