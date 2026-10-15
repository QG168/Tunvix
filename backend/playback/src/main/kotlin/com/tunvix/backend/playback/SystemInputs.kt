package com.tunvix.backend.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** 百分比与系统真实音量档位的纯映射，边界不越界。 */
internal object VolumeMapper {

    /** 百分比 [0,100] → 档位 [0,max]；超出范围自动收敛到边界。 */
    fun percentToStep(percent: Int, max: Int): Int {
        if (max <= 0) return 0
        val p = percent.coerceIn(0, 100)
        return ((p.toDouble() / 100.0) * max + 0.5).toInt().coerceIn(0, max)
    }

    /** 档位 [0,max] → 百分比 [0,100]。 */
    fun stepToPercent(step: Int, max: Int): Int {
        if (max <= 0) return 0
        return (((step.coerceIn(0, max)).toDouble() / max) * 100.0 + 0.5).toInt().coerceIn(0, 100)
    }
}

/** 系统媒体音量（STREAM_MUSIC）抽象，便于单元测试替换。 */
internal interface SystemVolume {
    fun maxStep(): Int
    fun currentStep(): Int
    fun setStep(step: Int)
    fun setObserver(observer: (() -> Unit)?)
    fun dispose() {}
}

/** AudioManager 实现：监听 Settings.System 以覆盖音量侧键等系统级变化。 */
internal class AndroidSystemVolume(context: Context) : SystemVolume {

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observer: (() -> Unit)? = null

    private val contentObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            observer?.invoke()
        }
    }

    init {
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, contentObserver)
    }

    override fun maxStep(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun currentStep(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun setStep(step: Int) {
        val max = maxStep()
        if (max <= 0) return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step.coerceIn(0, max), 0)
    }

    override fun setObserver(o: (() -> Unit)?) {
        observer = o
    }

    override fun dispose() {
        app.contentResolver.unregisterContentObserver(contentObserver)
    }
}

internal enum class FocusChange { GAINED, LOST, LOST_TRANSIENT }

/** 音频焦点抽象。 */
internal interface SystemFocus {
    /** 申请焦点；返回是否成功获得。 */
    fun request(): Boolean
    fun abandon()
    fun setListener(listener: ((FocusChange) -> Unit)?)
}

internal class AndroidSystemFocus(context: Context) : SystemFocus {

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: ((FocusChange) -> Unit)? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val mapped = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAINED
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> FocusChange.LOST_TRANSIENT
            else -> FocusChange.LOST
        }
        mainHandler.post { listener?.invoke(mapped) }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

    override fun request(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun setListener(l: ((FocusChange) -> Unit)?) {
        listener = l
    }
}

/** 耳机断开（AUDIO_BECOMING_NOISY）监听抽象。 */
internal interface NoisyMonitor {
    fun start(callback: () -> Unit)
    fun stop()
}

internal class AndroidNoisyMonitor(context: Context) : NoisyMonitor {

    private val app = context.applicationContext
    private var callback: (() -> Unit)? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) callback?.invoke()
        }
    }

    override fun start(cb: () -> Unit) {
        callback = cb
        if (!registered) {
            // 系统保护广播，无需 RECEIVER_EXPORTED 标志
            app.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            registered = true
        }
    }

    override fun stop() {
        callback = null
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }
}
