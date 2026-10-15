package com.tunvix.backend.playback

/**
 * 引擎层错误：与 Media3 类型解耦，控制器与单测只依赖本模型。
 * [detail] 只允许携带错误码名，禁止携带播放 URL / token，避免日志泄漏。
 */
internal data class EngineError(
    val kind: Kind,
    val httpStatus: Int? = null,
    val detail: String? = null,
) {
    enum class Kind { NETWORK, SOURCE_UNAVAILABLE, FORMAT, DECODE, STREAM_ENDED, UNKNOWN }

    /** 仅网络类断流与流中断做有限自动重试；地址失效等不自动重试。 */
    val recoverable: Boolean
        get() = kind == Kind.NETWORK || kind == Kind.STREAM_ENDED
}

/**
 * 引擎回调。[id] 是控制器在 [PlayerEngine.prepare] 时分配的准备序号，
 * 引擎必须在每个回调中回传该序号，控制器据此丢弃过期回调（快速切台隔离）。
 */
internal interface EngineCallback {
    fun onReady(id: Int)
    fun onBuffering(id: Int)
    fun onPlaying(id: Int)
    fun onError(id: Int, error: EngineError)
    fun onVideoAspectRatio(id: Int, ratio: Float)
    fun onFrameRendered(id: Int) {}
}

/**
 * 播放引擎抽象。单一实现持有单一播放器实例：
 * Surface 重绑定不重建播放器、不重连；前台重连复用同一实例。
 */
internal interface PlayerEngine {
    fun prepare(id: Int, url: String, headers: Map<String, String>)

    /** 设置 playWhenReady = true（等价播放）。 */
    fun play()

    /** 设置 playWhenReady = false（等价暂停）。 */
    fun pause()

    /** 停止拉流但保留播放器实例；前台重连时由控制器重新 prepare 回到直播位置。 */
    fun stopStream()

    fun attachSurface(surface: android.view.SurfaceView?)
    fun attachTexture(surface: android.view.TextureView?) {}
    fun detachSurface()
    fun setAudioOnly(enabled: Boolean) {}
    fun setChannelMetadata(channel: com.tunvix.contract.Channel) {}
    fun release()
}

/** 引擎错误 → 用户可展示文案与契约字段（[com.tunvix.contract.PlaybackSnapshot.mayBeExpired]）。 */
internal object PlaybackErrors {

    data class Mapped(val message: String, val code: String, val mayBeExpired: Boolean)

    fun map(error: EngineError): Mapped = when (error.kind) {
        EngineError.Kind.NETWORK ->
            Mapped("网络连接不稳定，请检查网络后重试", "NETWORK", mayBeExpired = false)
        EngineError.Kind.SOURCE_UNAVAILABLE ->
            Mapped("直播地址不可用或已过期，请刷新直播源", "SOURCE_EXPIRED", mayBeExpired = true)
        EngineError.Kind.FORMAT ->
            Mapped("直播流格式不受支持，无法播放", "FORMAT_UNSUPPORTED", mayBeExpired = false)
        EngineError.Kind.DECODE ->
            Mapped("音视频解码失败，无法播放该频道", "DECODE_FAILED", mayBeExpired = false)
        EngineError.Kind.STREAM_ENDED ->
            Mapped("直播流已中断，请重试", "STREAM_ENDED", mayBeExpired = false)
        EngineError.Kind.UNKNOWN ->
            Mapped("播放失败，请稍后重试", "UNKNOWN", mayBeExpired = false)
    }
}
