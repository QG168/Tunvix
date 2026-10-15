package com.tunvix.backend.catalog

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tunvix.contract.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Android implementation of [UriAccess]: persisted content permissions plus a direct probe. */
internal class AndroidUriAccess(context: Context) : UriAccess {

    private val resolver = context.contentResolver

    override fun canRead(uri: String): Boolean = runCatching {
        val parsed = Uri.parse(uri)
        when (parsed.scheme?.lowercase()) {
            null, "file" -> File(parsed.path ?: uri).canRead()
            "content" -> resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == parsed } ||
                runCatching { resolver.openInputStream(parsed)?.close() }.isSuccess
            else -> true
        }
    }.getOrDefault(false)
}

/** Plays silently from the stream's default/live position and copies the first queued frame. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AndroidThumbnailSource(private val context: Context) : ThumbnailSource {
    override suspend fun fetch(channel: Channel): CaptureResult {
        return try { capture(channel) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { CaptureResult(null) }
    }

    private suspend fun capture(channel: Channel): CaptureResult = withContext(Dispatchers.Main.immediate) {
        val http=androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(channel.headers.entries.firstOrNull { it.key.equals("User-Agent",ignoreCase=true) }?.value ?: "Tunvix/0.1")
            .setConnectTimeoutMs(2500).setReadTimeoutMs(3000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(channel.headers.filterKeys { !it.equals("User-Agent",ignoreCase=true) })
        // Detached SurfaceTexture accepts decoder output without a visible view or EGL drawing.
        // PixelCopy reads the latest queued buffer directly and scales it into the small bitmap.
        val texture=android.graphics.SurfaceTexture(false).apply { setDefaultBufferSize(320,180) }
        val surface=android.view.Surface(texture)
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        try {
            val capturePlayer=androidx.media3.exoplayer.ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
                .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(250,1500,0,0).build())
                .build()
            player=capturePlayer
            capturePlayer.volume=0f
            capturePlayer.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,false)
            capturePlayer.trackSelectionParameters=capturePlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO,true)
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT,true)
                .setMaxVideoSize(1280,720).setForceLowestBitrate(true).build()
            capturePlayer.setVideoSurface(surface)
            var firstFrameMillis: Long? = null
            val bitmap=kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { continuation ->
                val handler=android.os.Handler(android.os.Looper.getMainLooper())
                var width=320;var height=180
                var fallbackUsed=false
                var copying=false
                var copyAttempts=0
                val startedAt=android.os.SystemClock.elapsedRealtimeNanos()
                fun prepare(mime: String? = null) {
                    val media=androidx.media3.common.MediaItem.Builder().setUri(channel.url)
                        .apply { mime?.let { setMimeType(it) } }.build()
                    capturePlayer.setMediaItem(media)
                    capturePlayer.prepare()
                    capturePlayer.play()
                }
                fun finish(value: Bitmap?) {
                    if(continuation.isActive)continuation.resume(value) { _, discarded, _ -> discarded?.recycle() }
                    else value?.recycle()
                }
                fun copyFrame() {
                    if(!continuation.isActive || copying)return
                    copying=true
                    copyAttempts++
                    val scale=minOf(1f,320f/width,180f/height)
                    val destination=Bitmap.createBitmap((width*scale).toInt().coerceAtLeast(1),
                        (height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    try {
                        android.view.PixelCopy.request(surface,destination,{ result ->
                            copying=false
                            if(result==android.view.PixelCopy.SUCCESS)finish(destination)
                            else {
                                destination.recycle()
                                if(continuation.isActive && result==android.view.PixelCopy.ERROR_SOURCE_NO_DATA && copyAttempts<3)
                                    handler.postDelayed({ copyFrame() },20)
                                else finish(null)
                            }
                        },handler)
                    } catch (_: IllegalArgumentException) {
                        copying=false
                        destination.recycle()
                        finish(null)
                    }
                }
                capturePlayer.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                        if(size.width>0 && size.height>0) {
                            width=(size.width*size.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                            height=size.height
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        if(firstFrameMillis==null)firstFrameMillis=(android.os.SystemClock.elapsedRealtimeNanos()-startedAt)/1_000_000L
                        copyFrame()
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val formatError=generateSequence<Throwable>(error) { it.cause }
                            .any { it is androidx.media3.exoplayer.source.UnrecognizedInputFormatException }
                        if(formatError && !fallbackUsed && continuation.isActive) {
                            fallbackUsed=true
                            prepare(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                        } else finish(null)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if(state==androidx.media3.common.Player.STATE_ENDED && firstFrameMillis==null)finish(null)
                    }
                })
                // No seek to zero: a live source starts at its normal live/default position.
                prepare()
            } ?: return@withContext CaptureResult(null)
            // Release scarce 4K decoder resources before JPEG compression or catalog writes.
            capturePlayer.release()
            player=null
            try {
                CaptureResult(withContext(Dispatchers.IO) { compress(bitmap) },firstFrameMillis)
            } finally { bitmap.recycle() }
        } finally {
            player?.release()
            surface.release()
            texture.release()
        }
    }

    private fun compress(bitmap: Bitmap): ByteArray? = ByteArrayOutputStream().use { output ->
        if(bitmap.compress(Bitmap.CompressFormat.JPEG,65,output))output.toByteArray() else null
    }
}
