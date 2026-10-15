package com.tunvix.app.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Reads the video surface alone: controls and page chrome never enter the thumbnail. */
internal suspend fun capturePlaybackStill(surface: SurfaceView): ByteArray? {
    if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) return null
    val scale=minOf(320f/surface.width,180f/surface.height,1f)
    val bitmap=withTimeoutOrNull(1500) {
        suspendCancellableCoroutine<Bitmap?> { continuation ->
            val destination=Bitmap.createBitmap((surface.width*scale).toInt().coerceAtLeast(1),
                (surface.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(surface,destination,{ result ->
                    // PixelCopy may still be writing after cancellation: release only in its callback.
                    if(result==PixelCopy.SUCCESS && continuation.isActive) {
                        continuation.resume(destination) { _, value, _ -> value?.recycle() }
                    } else {
                        destination.recycle()
                        if(continuation.isActive)continuation.resume(null)
                    }
                },Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                destination.recycle()
                if(continuation.isActive)continuation.resume(null)
            }
        }
    } ?: return null
    return try {
        withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { output ->
                if(bitmap.compress(Bitmap.CompressFormat.JPEG,65,output))output.toByteArray() else null
            }
        }
    } finally { bitmap.recycle() }
}
