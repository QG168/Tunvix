package com.tunvix.backend.playback

import android.os.SystemClock
import android.content.Context
import android.view.SurfaceView
import com.tunvix.contract.ChannelLines
import com.tunvix.contract.Channel
import com.tunvix.contract.PlaybackController
import com.tunvix.contract.PlaybackPhase
import com.tunvix.contract.PlaybackSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 契约 [PlaybackController] 的 Media3 实现。
 *
 * 线程约定：所有公开方法要求在主线程调用；引擎回调与系统音量/焦点回调
 * 也统一投递到主线程，[snapshot] 的更新因此天然串行。
 *
 * 状态模型：
 * - 快速切台：每次 play() 递增频道代次、每次 prepare 递增准备序号，
 *   旧频道的任何回调都会因序号不匹配被丢弃，不可能覆盖新频道状态。
 * - 暂停原因严格区分：用户主动暂停（userPaused）、后台挂起
 *   （suspendedByBackground）、临时焦点丢失（pausedByTransientFocus）、
 *   需手动恢复的永久焦点丢失/耳机断开（systemPaused）。
 * - 断流自动重试仅针对网络类错误，有限次数 + 递增退避，绝不自动切台。
 */
class Media3PlaybackController internal constructor(
    private val sysVolume: SystemVolume,
    private val focus: SystemFocus,
    private val noisy: NoisyMonitor,
    mainDispatcher: CoroutineDispatcher,
    engineFactory: (EngineCallback) -> PlayerEngine,
) : PlaybackController {

    private val scope = CoroutineScope(
        SupervisorJob() + mainDispatcher + CoroutineName("TunvixPlayback")
    )
    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    /** 私有桥接：引擎回调统一走此对象，避免公开类型暴露 internal 接口。 */
    private val engineCallback: EngineCallback = object : EngineCallback {
        override fun onReady(id: Int) = onEngineReady(id)
        override fun onBuffering(id: Int) = onEngineBuffering(id)
        override fun onPlaying(id: Int) = onEnginePlaying(id)
        override fun onError(id: Int, error: EngineError) = onEngineError(id, error)
        override fun onVideoAspectRatio(id: Int, ratio: Float) = onEngineAspectRatio(id, ratio)
        override fun onFrameRendered(id: Int) { if (isCurrent(id)) update { it.copy(videoFrameReady = true) } }
    }

    private val engine = engineFactory(engineCallback)

    private var channelQueue: List<Channel> = emptyList()
    internal var onChannelQueueChanged: (() -> Unit)? = null
    internal val canSkipChannel get() = currentChannel != null && channelQueue.size > 1
    private var currentChannel: Channel? = null
    private var generation = 0
    private var prepareId = 0
    private var autoRetriesLeft = 0

    private var userPaused = false
    private var suspendedByBackground = false
    private var pausedByTransientFocus = false
    // Permanent focus loss and headphone disconnect require an explicit user resume.
    private var systemPaused = false
    private var needsPrepare = false

    private var lastUrl: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()
    private var sleepTimerJob: Job? = null
    internal var scheduleSleepAlarm: ((Long?) -> Unit)? = null
    internal var onSleepTimerExpired: (() -> Unit)? = null
    private var retryJob: Job? = null
    private var currentSurface: SurfaceView? = null
    private var currentTexture: android.view.TextureView? = null
    private var released = false
    private var backgroundPlayback = false
    internal var serviceOwned = false
    internal var uiOwned = false
    internal var onDisposed: (() -> Unit)? = null
    internal var beforePlay: (() -> Unit)? = null
    internal var beforeResume: (() -> Unit)? = null
    internal val sessionPlayer get() = (engine as Media3PlayerEngine).player

    init {
        sysVolume.setObserver { refreshVolume() }
        focus.setListener(::onFocusChange)
        noisy.start(::onNoisy)
        publishVolume()
    }

    companion object {
        const val MAX_AUTO_RETRIES = 3
        const val RETRY_BACKOFF_BASE_MS = 500L

        /** 前端 AppServices 唯一装配入口。 */
        fun create(
            context: Context,
            mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ): Media3PlaybackController {
            val app = context.applicationContext
            return Media3PlaybackController(
                sysVolume = AndroidSystemVolume(app),
                focus = AndroidSystemFocus(app),
                noisy = AndroidNoisyMonitor(app),
                mainDispatcher = mainDispatcher,
            ) { callback -> Media3PlayerEngine(app, callback) }
        }
    }

    // ---------- 播放控制 ----------

    override fun play(channel: Channel) {
        if (released) return
        beforePlay?.invoke()
        retryJob?.cancel()
        generation += 1
        currentChannel = channel
        engine.setChannelMetadata(channel)
        lastUrl = channel.url
        lastHeaders = channel.headers
        userPaused = false
        suspendedByBackground = false
        pausedByTransientFocus = false
        systemPaused = false
        autoRetriesLeft = MAX_AUTO_RETRIES
        val focusGranted = acquireFocus()
        currentSurface?.let { engine.attachSurface(it) }
        currentTexture?.let { engine.attachTexture(it) }
        startPrepare(autoplay = focusGranted)
    }

    override fun setChannelQueue(channels: List<Channel>) {
        if (released || channelQueue == channels) return
        channelQueue = channels.toList()
        onChannelQueueChanged?.invoke()
    }

    internal fun skipChannel(delta: Int) {
        val current = currentChannel ?: return
        if (released || channelQueue.size < 2) return
        val exact = channelQueue.indexOfFirst { it.id == current.id }
        val index = if (exact >= 0) exact else channelQueue.indexOfFirst { ChannelLines.sameChannel(it, current) }
        val targetIndex = if (index >= 0) (index + delta + channelQueue.size) % channelQueue.size else if (delta > 0) 0 else channelQueue.lastIndex
        val target = channelQueue[targetIndex]
        val listening = snapshot.value.audioOnly
        play(target)
        if (listening) setAudioOnly(true)
    }

    override fun retry() {
        if (released || currentChannel == null) return
        retryJob?.cancel()
        autoRetriesLeft = MAX_AUTO_RETRIES
        userPaused = false
        suspendedByBackground = false
        pausedByTransientFocus = false
        systemPaused = false
        val focusGranted = acquireFocus()
        startPrepare(autoplay = focusGranted)
    }

    override fun pause() {
        if (released) return
        val phase = _snapshot.value.phase
        if (phase != PlaybackPhase.PLAYING && phase != PlaybackPhase.CONNECTING) return
        userPaused = true
        retryJob?.cancel()
        engine.pause()
        update { it.copy(phase = PlaybackPhase.PAUSED) }
        focus.abandon()
    }

    override fun resume() {
        if (released || currentChannel == null) return
        val phase = _snapshot.value.phase
        if (phase == PlaybackPhase.FAILED) {
            // 失败后的“继续观看”等价重试
            retry()
            return
        }
        if (!mustStayPaused() && phase != PlaybackPhase.PAUSED) return
        userPaused = false
        pausedByTransientFocus = false
        systemPaused = false
        // 焦点被拒：保持暂停，等待焦点恢复（pausedByTransientFocus 已置位）
        if (!acquireFocus()) return
        update { it.copy(phase = PlaybackPhase.CONNECTING) }
        if (suspendedByBackground || needsPrepare) {
            suspendedByBackground = false
            startPrepare(autoplay = true)
        } else {
            engine.play()
        }
    }

    override fun stop() {
        if (released) return
        cancelSleepTimer()
        retryJob?.cancel()
        generation += 1
        prepareId += 1 // 之后到达的一切引擎回调均过期
        userPaused = false
        suspendedByBackground = false
        pausedByTransientFocus = false
        systemPaused = false
        needsPrepare = false
        engine.stopStream()
        engine.setAudioOnly(false)
        backgroundPlayback = false
        currentChannel = null
        lastUrl = null
        focus.abandon()
        update {
            it.copy(channelId = null, phase = PlaybackPhase.IDLE, audioOnly = false, videoFrameReady = false, errorMessage = null, mayBeExpired = false)
        }
    }

    // ---------- 生命周期 ----------

    override fun setAudioOnly(enabled: Boolean) {
        if (released || currentChannel == null) return
        if (!enabled) cancelSleepTimer()
        engine.setAudioOnly(enabled)
        update { it.copy(audioOnly = enabled, videoFrameReady = false) }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel(); sleepTimerJob = null
        scheduleSleepAlarm?.invoke(null)
        update { it.copy(sleepTimerDeadlineElapsed = null, sleepTimerRemainingSeconds = 0, sleepTimerMinutes = 0) }
    }

    override fun setSleepTimer(minutes: Int) {
        if (released) return
        cancelSleepTimer()
        if (minutes !in setOf(15, 30, 45, 60) || !snapshot.value.audioOnly) return
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        update { it.copy(sleepTimerDeadlineElapsed = deadline, sleepTimerRemainingSeconds = minutes * 60L, sleepTimerMinutes = minutes) }
        scheduleSleepAlarm?.invoke(deadline)
        sleepTimerJob = scope.launch {
            while (snapshot.value.sleepTimerDeadlineElapsed == deadline) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) { expireSleepTimer(deadline); break }
                update { it.copy(sleepTimerRemainingSeconds = (remaining + 999) / 1000) }
                delay(minOf(1000L, remaining))
            }
        }
    }

    internal fun expireSleepTimer(deadline: Long) {
        if (released || snapshot.value.sleepTimerDeadlineElapsed != deadline || SystemClock.elapsedRealtime() < deadline) return
        val action = onSleepTimerExpired
        if (action != null) action() else stop()
    }

    override fun setBackgroundPlayback(enabled: Boolean) { backgroundPlayback = enabled }

    override fun onBackground() {
        if (released || currentChannel == null || backgroundPlayback || snapshot.value.audioOnly) return
        // 退避重连任务不得在后台启动拉流：进入后台立即取消
        retryJob?.cancel()
        retryJob = null
        when (_snapshot.value.phase) {
            PlaybackPhase.PLAYING, PlaybackPhase.CONNECTING -> {
                suspendedByBackground = true
                needsPrepare = true
                engine.stopStream()
            }
            PlaybackPhase.PAUSED -> {
                // 保留 userPaused 标记：前台恢复后仍停在暂停态
                suspendedByBackground = true
                needsPrepare = true
                engine.stopStream()
            }
            else -> Unit
        }
        focus.abandon()
    }

    override fun onForeground() {
        if (released || currentChannel == null) return
        if (!suspendedByBackground) return
        suspendedByBackground = false
        if (userPaused || systemPaused) {
            // 用户主动暂停或系统永久暂停：重新拉流但停留在暂停态，不覆盖暂停意图
            startPrepare(autoplay = false)
        } else {
            val focusGranted = acquireFocus()
            startPrepare(autoplay = focusGranted) // 直播回到最新位置
        }
    }

    override fun release() {
        uiOwned = false
        if (serviceOwned) return
        dispose()
    }

    internal fun dispose() {
        if (released) return
        cancelSleepTimer()
        released = true
        retryJob?.cancel()
        scope.cancel()
        generation += 1
        prepareId += 1
        sysVolume.setObserver(null)
        sysVolume.dispose()
        noisy.stop()
        focus.abandon()
        engine.release()
        onDisposed?.invoke()
    }

    // ---------- Surface ----------

    override fun attachSurface(surface: SurfaceView) {
        currentTexture = null
        currentSurface = surface
        engine.attachSurface(surface)
    }

    override fun detachSurface(surface: SurfaceView) {
        if (currentSurface === surface) {
            currentSurface = null
            engine.detachSurface()
        }
    }

    override fun attachTexture(surface: android.view.TextureView) {
        currentSurface = null; currentTexture = surface; engine.attachTexture(surface)
    }
    override fun detachTexture(surface: android.view.TextureView) {
        if (currentTexture === surface) { currentTexture = null; engine.detachSurface() }
    }

    // ---------- 音量 ----------

    override fun setVolume(percent: Int) {
        if (released) return
        val max = sysVolume.maxStep()
        if (max <= 0) return
        sysVolume.setStep(VolumeMapper.percentToStep(percent, max))
        publishVolume()
    }

    override fun refreshVolume() {
        if (released) return
        publishVolume()
    }

    private fun publishVolume() {
        val max = sysVolume.maxStep()
        if (max <= 0) return
        val percent = VolumeMapper.stepToPercent(sysVolume.currentStep(), max)
        update { it.copy(volumePercent = percent) }
    }

    // ---------- 引擎回调（经 engineCallback 桥接） ----------

    private fun onEngineReady(id: Int) {
        if (!isCurrent(id)) return
        if (mustStayPaused()) {
            engine.pause()
            update { it.copy(phase = PlaybackPhase.PAUSED) }
        } else {
            engine.play()
        }
    }

    private fun onEngineBuffering(id: Int) {
        if (!isCurrent(id) || mustStayPaused()) return
        if (_snapshot.value.phase != PlaybackPhase.PAUSED) {
            update { it.copy(phase = PlaybackPhase.CONNECTING) }
        }
    }

    private fun onEnginePlaying(id: Int) {
        if (!isCurrent(id)) return
        if (mustStayPaused()) {
            engine.pause()
            update { it.copy(phase = PlaybackPhase.PAUSED) }
            return
        }
        autoRetriesLeft = MAX_AUTO_RETRIES // 成功出画面后重置自动重试预算
        update {
            it.copy(phase = PlaybackPhase.PLAYING, errorMessage = null, mayBeExpired = false)
        }
    }

    private fun onEngineError(id: Int, error: EngineError) {
        if (!isCurrent(id)) return
        // Errors leave the real engine unprepared, even if a pause suppresses automatic retry.
        needsPrepare = true
        // Paused/background states defer reconnection until an allowed resume.
        if (mustStayPaused()) return
        retryJob?.cancel()
        if (autoRetriesLeft > 0 && error.recoverable) {
            autoRetriesLeft -= 1
            val attempt = MAX_AUTO_RETRIES - autoRetriesLeft
            update {
                it.copy(
                    phase = PlaybackPhase.CONNECTING,
                    errorMessage = "网络波动，正在重连（$attempt/$MAX_AUTO_RETRIES）",
                )
            }
            val expectedPrepareId = id
            retryJob = scope.launch {
                delay(RETRY_BACKOFF_BASE_MS * attempt)
                // 再次确认不在后台：避免退避期间进入后台后仍拉流
                if (!mustStayPaused() && isCurrent(expectedPrepareId)) {
                    val focusGranted = acquireFocus()
                    startPrepare(autoplay = focusGranted)
                }
            }
        } else {
            focus.abandon()
            val mapped = PlaybackErrors.map(error)
            update {
                it.copy(
                    phase = PlaybackPhase.FAILED,
                    errorMessage = mapped.message,
                    mayBeExpired = mapped.mayBeExpired,
                )
            }
        }
    }

    private fun onEngineAspectRatio(id: Int, ratio: Float) {
        if (!isCurrent(id) || !ratio.isFinite() || ratio <= 0f) return
        update { it.copy(videoAspectRatio = ratio) }
    }

    // ---------- 私有辅助 ----------

    private fun startPrepare(autoplay: Boolean) {
        val url = lastUrl ?: return
        prepareId += 1
        needsPrepare = false
        update {
            it.copy(
                channelId = currentChannel?.id,
                phase = PlaybackPhase.CONNECTING,
                errorMessage = null,
                mayBeExpired = false,
                videoFrameReady = false,
            )
        }
        engine.prepare(prepareId, url, lastHeaders)
        if (autoplay) engine.play() else engine.pause()
    }

    private fun isCurrent(id: Int): Boolean =
        !released && currentChannel != null && id == prepareId

    /**
     * 申请音频焦点；被拒时按「暂时丢失」处理：prepare 完成后保持暂停，
     * 不自动出声，待焦点恢复（GAINED）再续播。
     */
    private fun acquireFocus(): Boolean {
        beforeResume?.invoke()
        val granted = focus.request()
        pausedByTransientFocus = !granted
        return granted
    }

    private fun mustStayPaused(): Boolean =
        userPaused || suspendedByBackground || pausedByTransientFocus || systemPaused

    private fun onNoisy() {
        if (released || currentChannel == null) return
        if (_snapshot.value.phase == PlaybackPhase.PLAYING || _snapshot.value.phase == PlaybackPhase.CONNECTING) {
            systemPaused = true
            retryJob?.cancel()
            engine.pause()
            update { it.copy(phase = PlaybackPhase.PAUSED) }
        }
    }

    private fun onFocusChange(change: FocusChange) {
        if (released || currentChannel == null) return
        when (change) {
            FocusChange.GAINED -> {
                if (pausedByTransientFocus) {
                    pausedByTransientFocus = false
                    if (!mustStayPaused() && _snapshot.value.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.CONNECTING)) {
                        if (needsPrepare) startPrepare(autoplay = true)
                        else {
                            engine.play()
                            update { it.copy(phase = PlaybackPhase.CONNECTING) }
                        }
                    }
                }
            }
            FocusChange.LOST, FocusChange.LOST_TRANSIENT -> {
                if (_snapshot.value.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.CONNECTING, PlaybackPhase.PAUSED)) {
                    retryJob?.cancel()
                    pausedByTransientFocus = change == FocusChange.LOST_TRANSIENT
                    if (change == FocusChange.LOST) systemPaused = true
                    engine.pause()
                    update { it.copy(phase = PlaybackPhase.PAUSED) }
                }
            }
        }
    }

    private fun update(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        _snapshot.value = transform(_snapshot.value)
    }
}
