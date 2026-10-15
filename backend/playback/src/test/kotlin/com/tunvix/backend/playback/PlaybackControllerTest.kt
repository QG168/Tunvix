package com.tunvix.backend.playback

import com.tunvix.contract.Channel
import com.tunvix.contract.PlaybackPhase
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackControllerTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var engine: FakeEngine
    private lateinit var volume: FakeVolume
    private lateinit var focus: FakeFocus
    private lateinit var noisy: FakeNoisy
    private lateinit var controller: Media3PlaybackController

    private val chA = Channel(
        id = "a", sourceId = "s", name = "A",
        url = "http://example.com/a.m3u8", headers = mapOf("Referer" to "http://ref.example"),
    )
    private val chB = Channel(
        id = "b", sourceId = "s", name = "B",
        url = "http://example.com/live/b", headers = mapOf("User-Agent" to "TestUA"),
    )

    @Before
    fun setUp() {
        volume = FakeVolume(max = 100, current = 50)
        focus = FakeFocus()
        noisy = FakeNoisy()
        var created: FakeEngine? = null
        controller = Media3PlaybackController(
            sysVolume = volume,
            focus = focus,
            noisy = noisy,
            mainDispatcher = dispatcher,
        ) { callback -> FakeEngine(callback).also { created = it } }
        engine = created!!
    }

    @After
    fun tearDown() {
        controller.release()
    }

    // ---------- 基本状态流 ----------

    @Test
    fun `play enters connecting then playing`() {
        controller.play(chA)
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
        assertEquals("a", controller.snapshot.value.channelId)
        assertNull(controller.snapshot.value.errorMessage)

        val prepare = engine.prepares.single()
        assertEquals(chA.url, prepare.url)
        assertEquals(chA.headers, prepare.headers)

        engine.emitReady(prepare.id)
        assertEquals(2, engine.playCount) // startPrepare 一次 + ready 确认一次

        engine.emitPlaying(prepare.id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    @Test
    fun `pause and resume keep user intent`() {
        playToPlaying(chA)
        val id = engine.prepares.last().id

        controller.pause()
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
        assertEquals(1, engine.pauseCount)

        controller.resume()
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
        assertEquals(3, engine.playCount) // playToPlaying 2 次 + resume 1 次

        engine.emitPlaying(id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    // ---------- 快速切台：旧回调隔离 ----------

    @Test
    fun `stale callbacks from previous channel are ignored`() {
        controller.play(chA)
        val idA = engine.prepares.last().id
        controller.play(chB)
        val idB = engine.prepares.last().id
        assertNotEquals(idA, idB)

        // 旧频道 A 的错误、就绪事件都不允许影响新频道 B 的状态
        engine.emitError(idA, EngineError(EngineError.Kind.NETWORK))
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
        assertEquals("b", controller.snapshot.value.channelId)
        assertNull(controller.snapshot.value.errorMessage)

        engine.emitReady(idA)
        assertEquals(2, engine.playCount) // 仅 A、B 两次 startPrepare 触发过 play，旧 ready 无效

        engine.emitReady(idB)
        assertEquals(3, engine.playCount)
        engine.emitPlaying(idB)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    // ---------- 断流自动重试 ----------

    @Test
    fun `network errors retry with backoff then fail after limit`() {
        val scheduler = dispatcher.scheduler
        controller.play(chA)
        var id = engine.prepares.last().id

        repeat(Media3PlaybackController.MAX_AUTO_RETRIES) { n ->
            engine.emitError(id, EngineError(EngineError.Kind.NETWORK))
            assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
            scheduler.advanceTimeBy(Media3PlaybackController.RETRY_BACKOFF_BASE_MS * (n + 1))
            scheduler.runCurrent()
            assertEquals(n + 2, engine.prepares.size) // 原始 + 重连次数
            id = engine.prepares.last().id
        }

        engine.emitError(id, EngineError(EngineError.Kind.NETWORK))
        assertEquals(PlaybackPhase.FAILED, controller.snapshot.value.phase)
        assertTrue(controller.snapshot.value.errorMessage?.contains("重试") == true)
        assertTrue(!controller.snapshot.value.mayBeExpired)
    }

    @Test
    fun `non recoverable error fails immediately and marks mayBeExpired`() {
        controller.play(chA)
        engine.emitError(
            engine.prepares.last().id,
            EngineError(EngineError.Kind.SOURCE_UNAVAILABLE, httpStatus = 403),
        )
        assertEquals(PlaybackPhase.FAILED, controller.snapshot.value.phase)
        assertTrue(controller.snapshot.value.mayBeExpired)
        assertEquals(1, engine.prepares.size) // 未做自动重试
    }

    @Test
    fun `retry re-prepares current channel only`() {
        controller.play(chA)
        controller.play(chB)
        engine.emitError(
            engine.prepares.last().id,
            EngineError(EngineError.Kind.SOURCE_UNAVAILABLE, httpStatus = 404),
        )
        assertEquals(PlaybackPhase.FAILED, controller.snapshot.value.phase)

        val before = engine.prepares.size
        controller.retry()
        val prepare = engine.prepares.last()
        assertEquals(before + 1, engine.prepares.size)
        assertEquals(chB.url, prepare.url) // retry 只针对当前频道
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
    }

    @Test
    fun `resume on failed channel reconnects`() {
        controller.play(chA)
        engine.emitError(
            engine.prepares.last().id,
            EngineError(EngineError.Kind.SOURCE_UNAVAILABLE, httpStatus = 404),
        )
        assertEquals(PlaybackPhase.FAILED, controller.snapshot.value.phase)

        controller.resume()
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
        assertEquals(2, engine.prepares.size)
    }

    // ---------- 生命周期：后台 / 前台 ----------

    @Test
    fun `background stops stream and foreground returns to live`() {
        playToPlaying(chA)
        val before = engine.prepares.size

        controller.onBackground()
        assertEquals(1, engine.stopCount) // 后台停止拉流

        controller.onForeground()
        assertEquals(before + 1, engine.prepares.size)
        assertEquals(3, engine.playCount) // playToPlaying 2 次 + 前台自动重连 1 次
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)

        engine.emitReady(engine.prepares.last().id)
        engine.emitPlaying(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    @Test
    fun `user pause survives background and foreground`() {
        playToPlaying(chA)
        controller.pause() // 用户主动暂停

        controller.onBackground()
        assertEquals(1, engine.stopCount) // 即便暂停态，后台也停止拉流

        controller.onForeground()
        engine.emitReady(engine.prepares.last().id)
        // 保持暂停，不自动播放：用户主动暂停意图不被覆盖
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
        assertEquals(2, engine.playCount) // 仅 playToPlaying 的 2 次，前台未自动续播
    }

    @Test
    fun `stop returns to idle and invalidates callbacks`() {
        controller.play(chA)
        val id = engine.prepares.last().id

        controller.stop()
        assertEquals(PlaybackPhase.IDLE, controller.snapshot.value.phase)
        assertEquals(1, engine.stopCount)

        engine.emitPlaying(id)
        assertEquals(PlaybackPhase.IDLE, controller.snapshot.value.phase)
    }

    // ---------- 音量 ----------

    @Test
    fun `volume clamps at boundaries and follows system changes`() {
        volume.max = 15
        volume.current = 8
        controller.refreshVolume()
        assertEquals(53, controller.snapshot.value.volumePercent)

        controller.setVolume(50)
        assertEquals(8, volume.lastSet)
        controller.setVolume(150) // 上限不越界
        assertEquals(15, volume.lastSet)
        controller.setVolume(-1) // 下限不越界
        assertEquals(0, volume.lastSet)

        volume.changeTo(3) // 模拟音量侧键变化 → 观察者刷新快照
        assertEquals(20, controller.snapshot.value.volumePercent)
    }

    // ---------- 音频焦点与耳机 ----------

    @Test
    fun `transient focus loss pauses and gain resumes`() {
        playToPlaying(chA)

        focus.emit(FocusChange.LOST_TRANSIENT)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)

        focus.emit(FocusChange.GAINED)
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
        assertEquals(3, engine.playCount) // playToPlaying 2 次 + 焦点恢复 1 次
    }

    @Test
    fun `permanent focus loss does not auto resume`() {
        playToPlaying(chA)

        focus.emit(FocusChange.LOST)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)

        focus.emit(FocusChange.GAINED)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
    }

    @Test
    fun `headset disconnect pauses playback`() {
        playToPlaying(chA)

        noisy.callback?.invoke()
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
    }

    // ---------- 画面比例 ----------

    @Test
    fun `aspect ratio updates snapshot`() {
        controller.play(chA)
        engine.emitAspect(engine.prepares.last().id, 2.35f)
        assertEquals(2.35f, controller.snapshot.value.videoAspectRatio, 0.001f)
    }

    // ---------- 后台与重连竞态（审查 P1） ----------

    @Test
    fun `pending retry is cancelled when entering background`() {
        val scheduler = dispatcher.scheduler
        controller.play(chA)
        engine.emitError(engine.prepares.last().id, EngineError(EngineError.Kind.NETWORK))

        controller.onBackground()
        assertEquals(1, engine.stopCount)

        scheduler.advanceTimeBy(Media3PlaybackController.RETRY_BACKOFF_BASE_MS * 3 + 1)
        scheduler.runCurrent()
        assertEquals(1, engine.prepares.size) // 后台没有发起任何重连
        assertEquals(1, engine.playCount) // 也没有新的播放请求

        controller.onForeground() // 回前台只恢复一次
        assertEquals(2, engine.prepares.size)
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
        engine.emitReady(engine.prepares.last().id)
        engine.emitPlaying(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    @Test
    fun `error arriving during background does not trigger reconnect`() {
        playToPlaying(chA)
        val id = engine.prepares.last().id

        controller.onBackground()
        engine.emitError(id, EngineError(EngineError.Kind.NETWORK))

        // 后台到达的错误：不重试、不改状态
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
        assertEquals(1, engine.prepares.size)

        controller.onForeground()
        assertEquals(2, engine.prepares.size)
        engine.emitReady(engine.prepares.last().id)
        engine.emitPlaying(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    @Test
    fun `user pause intent preserved with single foreground reconnect`() {
        playToPlaying(chA)
        controller.pause()

        controller.onBackground()
        controller.onForeground()
        assertEquals(2, engine.prepares.size) // 仅一次恢复

        engine.emitReady(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)

        controller.resume() // 用户确认后才继续
        engine.emitPlaying(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    // ---------- 焦点申请被拒（审查 P2） ----------

    @Test
    fun `focus denied at play keeps silent until focus gained`() {
        focus.granted = false
        controller.play(chA)
        engine.emitReady(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
        assertEquals(0, engine.playCount) // 焦点被拒不自动出声

        focus.granted = true
        focus.emit(FocusChange.GAINED)
        assertEquals(PlaybackPhase.CONNECTING, controller.snapshot.value.phase)
        assertEquals(1, engine.playCount)
    }

    @Test
    fun `focus denied at resume stays paused`() {
        playToPlaying(chA)
        val plays = engine.playCount
        controller.pause()

        focus.granted = false
        controller.resume()
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
        assertEquals(plays, engine.playCount) // 未自动续播
    }

    @Test
    fun `focus denied at retry keeps silent when ready`() {
        controller.play(chA)
        engine.emitError(
            engine.prepares.last().id,
            EngineError(EngineError.Kind.SOURCE_UNAVAILABLE, httpStatus = 404),
        )
        assertEquals(PlaybackPhase.FAILED, controller.snapshot.value.phase)

        focus.granted = false
        controller.retry()
        engine.emitReady(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
        assertEquals(1, engine.playCount) // 仅首次 play 计数，retry 被拒不新增播放请求
    }

    @Test fun renderedFrameBelongsToCurrentPrepareAndResetsOnSwitch() {
        playToPlaying(chA)
        val old = engine.prepares.last().id
        assertEquals(false, controller.snapshot.value.videoFrameReady)
        engine.emitFrame(old)
        assertTrue(controller.snapshot.value.videoFrameReady)
        controller.play(chB)
        assertEquals(false, controller.snapshot.value.videoFrameReady)
        engine.emitFrame(old)
        assertEquals(false, controller.snapshot.value.videoFrameReady)
        engine.emitFrame(engine.prepares.last().id)
        assertTrue(controller.snapshot.value.videoFrameReady)
        controller.retry()
        assertEquals(false, controller.snapshot.value.videoFrameReady)
    }

    // ---------- 辅助 ----------

    private fun playToPlaying(channel: Channel) {
        controller.play(channel)
        val id = engine.prepares.last().id
        engine.emitReady(id)
        engine.emitPlaying(id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    private class FakeEngine(private val cb: EngineCallback) : PlayerEngine {
        data class Prepare(val id: Int, val url: String, val headers: Map<String, String>)

        val prepares = mutableListOf<Prepare>()
        var playCount = 0
        var pauseCount = 0
        var stopCount = 0
        var released = false

        fun emitReady(id: Int) = cb.onReady(id)
        fun emitPlaying(id: Int) = cb.onPlaying(id)
        fun emitFrame(id: Int) = cb.onFrameRendered(id)
        fun emitError(id: Int, error: EngineError) = cb.onError(id, error)
        fun emitAspect(id: Int, ratio: Float) = cb.onVideoAspectRatio(id, ratio)

        override fun prepare(id: Int, url: String, headers: Map<String, String>) {
            prepares += Prepare(id, url, headers)
        }

        override fun play() {
            playCount++
        }

        override fun pause() {
            pauseCount++
        }

        override fun stopStream() {
            stopCount++
        }

        override fun attachSurface(surface: android.view.SurfaceView?) = Unit

        override fun detachSurface() = Unit

        override fun release() {
            released = true
        }
    }

    private class FakeVolume(max: Int, current: Int) : SystemVolume {
        var max = max
        var current = current
        var lastSet: Int? = null
        var observerRef: (() -> Unit)? = null

        override fun maxStep() = max
        override fun currentStep() = current

        override fun setStep(step: Int) {
            lastSet = step
            current = step
        }

        override fun setObserver(o: (() -> Unit)?) {
            observerRef = o
        }

        fun changeTo(step: Int) {
            current = step
            observerRef?.invoke()
        }
    }

    private class FakeFocus : SystemFocus {
        var granted = true
        var requestCount = 0
        var abandonCount = 0
        var listenerRef: ((FocusChange) -> Unit)? = null

        override fun request(): Boolean {
            requestCount++
            return granted
        }

        override fun abandon() {
            abandonCount++
        }

        override fun setListener(l: ((FocusChange) -> Unit)?) {
            listenerRef = l
        }

        fun emit(change: FocusChange) {
            listenerRef?.invoke(change)
        }
    }

    private class FakeNoisy : NoisyMonitor {
        var callback: (() -> Unit)? = null

        override fun start(cb: () -> Unit) {
            callback = cb
        }

        override fun stop() {
            callback = null
        }
    }
    @Test fun permanentFocusLossBlocksLateReadyAndForegroundResume() {
        controller.play(chA)
        val id = engine.prepares.last().id
        focus.emit(FocusChange.LOST)
        val before = engine.playCount
        engine.emitReady(id)
        assertEquals(before, engine.playCount)
        focus.emit(FocusChange.GAINED)
        controller.onBackground(); controller.onForeground()
        engine.emitReady(engine.prepares.last().id)
        assertEquals(before, engine.playCount)
        controller.resume()
        assertTrue(engine.playCount > before)
    }

    @Test fun permanentFocusLossCancelsPendingRetry() {
        controller.play(chA)
        val id = engine.prepares.last().id
        engine.emitError(id, EngineError(EngineError.Kind.NETWORK))
        focus.emit(FocusChange.LOST)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, engine.prepares.size)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
    }

    @Test fun lateErrorAfterUserPauseDoesNotScheduleAutomaticPlayback() {
        playToPlaying(chA)
        controller.pause()
        val before = engine.playCount
        engine.emitError(engine.prepares.last().id, EngineError(EngineError.Kind.NETWORK))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(before, engine.playCount)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
    }

    @Test fun explicitChannelSelectionClearsPermanentSystemPause() {
        playToPlaying(chA)
        focus.emit(FocusChange.LOST)
        playToPlaying(chB)
        assertEquals("b", controller.snapshot.value.channelId)
    }

    @Test fun rapidBackgroundCyclesPreserveExplicitPauseBeforeReady() {
        playToPlaying(chA); controller.pause()
        val before = engine.playCount
        controller.onBackground(); controller.onForeground()
        controller.onBackground(); controller.onForeground()
        engine.emitReady(engine.prepares.last().id)
        assertEquals(before, engine.playCount)
        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
    }

    @Test fun userResumeAfterErrorAndFocusLossPreparesNewStream() {
        controller.play(chA)
        engine.emitError(engine.prepares.last().id, EngineError(EngineError.Kind.NETWORK))
        focus.emit(FocusChange.LOST)
        controller.resume()
        assertEquals(2, engine.prepares.size)
        engine.emitReady(engine.prepares.last().id)
        engine.emitPlaying(engine.prepares.last().id)
        assertEquals(PlaybackPhase.PLAYING, controller.snapshot.value.phase)
    }

    @Test fun errorDuringUserPauseWaitsForManualReconnect() {
        playToPlaying(chA); controller.pause()
        engine.emitError(engine.prepares.last().id, EngineError(EngineError.Kind.NETWORK))
        controller.resume()
        assertEquals(2, engine.prepares.size)
    }

}
