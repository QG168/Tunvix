package com.tunvix.backend.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException

/**
 * Media3/ExoPlayer 引擎封装。
 *
 * - 单一 [ExoPlayer] 实例贯穿整个控制器生命周期；Surface 重绑定不重建、不重连。
 * - 每频道 headers 通过动态 [DataSource.Factory] 注入（User-Agent 单独处理）。
 * - 媒体格式选择不依赖后缀：内容解析失败时在 HLS 与渐进式之间自动回退一次。
 */
@OptIn(UnstableApi::class)
internal class Media3PlayerEngine(
    context: Context,
    private val callback: EngineCallback,
) : PlayerEngine {

    private var activePrepareId = 0
    private var activeHeaders: Map<String, String> = emptyMap()
    private var activeUrl: String? = null
    private var mimeOverride: String? = null
    private var fallbackUsed = false
    private var metadata = androidx.media3.common.MediaMetadata.EMPTY

    /** 每次创建数据源时读取最新 headers，天然支持每频道不同请求头。 */
    private val httpDataSourceFactory = DataSource.Factory {
        val userAgent = activeHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value ?: DEFAULT_USER_AGENT
        val headers = activeHeaders.filterKeys { key ->
            key.isNotBlank() && !key.equals("User-Agent", ignoreCase = true)
        }
        DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
    }

    internal val player: ExoPlayer = ExoPlayer.Builder(context)
        // The extension contains only MP2; other formats retain the platform decoder.
        .setRenderersFactory(DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
        .build()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> callback.onBuffering(activePrepareId)
                Player.STATE_READY -> callback.onReady(activePrepareId)
                Player.STATE_ENDED -> callback.onError(
                    activePrepareId,
                    EngineError(EngineError.Kind.STREAM_ENDED, detail = "STATE_ENDED"),
                )
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) callback.onPlaying(activePrepareId)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (tryFallback(error)) return
            callback.onError(activePrepareId, mapError(error))
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                callback.onVideoAspectRatio(activePrepareId, ratio)
            }
        }
    }

    private val frameListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            val timeline=eventTime.timeline
            if(eventTime.windowIndex !in 0 until timeline.windowCount)return
            val mediaId=timeline.getWindow(eventTime.windowIndex,Timeline.Window()).mediaItem.mediaId
            renderedPrepareId(mediaId,activePrepareId)?.let(callback::onFrameRendered)
        }
    }

    init {
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(playerListener)
        player.addAnalyticsListener(frameListener)
    }

    override fun prepare(id: Int, url: String, headers: Map<String, String>) {
        activePrepareId = id
        activeHeaders = headers
        activeUrl = url
        mimeOverride = null
        fallbackUsed = false
        start(url, mimeOverride = null)
    }

    override fun setAudioOnly(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled).build()
    }

    override fun setChannelMetadata(channel: com.tunvix.contract.Channel) {
        metadata = androidx.media3.common.MediaMetadata.Builder().setTitle(channel.name)
            .setArtist("Tunvix · 直播").build()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stopStream() {
        // 停止加载与拉流；前台由控制器重新 prepare 回到直播位置
        player.stop()
        player.clearMediaItems()
    }

    override fun attachSurface(surface: android.view.SurfaceView?) {
        player.setVideoSurfaceView(surface)
    }

    override fun detachSurface() {
        player.setVideoSurfaceView(null)
    }

    override fun release() {
        player.removeListener(playerListener)
        player.removeAnalyticsListener(frameListener)
        player.release()
    }

    private fun start(url: String, mimeOverride: String?) {
        val item = MediaItem.Builder().setMediaId(activePrepareId.toString()).setUri(url).setMediaMetadata(metadata).apply {
            mimeOverride?.let { setMimeType(it) }
        }.build()
        player.setMediaItem(item)
        player.prepare()
    }

    /**
     * 格式自动回退：不凭后缀判断。无扩展名地址先按渐进式探测，
     * 读不出内容时强制按 HLS 重试；按 HLS 解析失败时回退渐进式。
     * 每个准备周期最多回退一次，避免循环重试。
     */
    private fun tryFallback(error: PlaybackException): Boolean {
        val url = activeUrl ?: return false
        if (fallbackUsed) return false
        val unrecognized = generateSequence<Throwable>(error) { it.cause }
            .any { it is UnrecognizedInputFormatException }
        return when {
            unrecognized && mimeOverride == null -> {
                fallbackUsed = true
                mimeOverride = MimeTypes.APPLICATION_M3U8
                start(url, mimeOverride)
                true
            }
            mimeOverride == MimeTypes.APPLICATION_M3U8 && error.errorCode in PARSING_ERROR_CODES -> {
                fallbackUsed = true
                mimeOverride = null
                start(url, null)
                true
            }
            else -> false
        }
    }

    private fun mapError(error: PlaybackException): EngineError {
        var status: Int? = null
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                status = cause.responseCode
                break
            }
            cause = cause.cause
        }
        val kind = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            -> EngineError.Kind.NETWORK

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> EngineError.Kind.SOURCE_UNAVAILABLE

            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> EngineError.Kind.FORMAT

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> EngineError.Kind.DECODE

            else -> EngineError.Kind.UNKNOWN
        }
        return EngineError(kind, status, detail = error.errorCodeName)
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "Tunvix/0.1"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        val PARSING_ERROR_CODES = intArrayOf(
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}
