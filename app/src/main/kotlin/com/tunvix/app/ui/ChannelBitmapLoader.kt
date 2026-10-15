package com.tunvix.app.ui
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext
private val bitmapScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
private val bitmapCache=SharedImageCache<Bitmap>(12*1024*1024,bitmapScope) { it.allocationByteCount }
internal fun cachedChannelBitmap(uri:String?):Bitmap? = uri?.let { bitmapCache.peek(it) }

/** Shared local decode and bounded artwork loading. Cached images never require network. */
internal suspend fun loadChannelBitmap(context:Context,uri:String,allowNetwork:Boolean):Bitmap? {
    bitmapCache.peek(uri)?.let { return it }
    val scheme=Uri.parse(uri).scheme?.lowercase()
    if(!allowNetwork && (scheme=="http" || scheme=="https"))return null
    val appContext=context.applicationContext
    return bitmapCache.load(uri) { decodeChannelBitmap(appContext,uri,allowNetwork) }
}

/** Bounded artwork loading; playback and screenshot generation belong to backend modules. */
private suspend fun decodeChannelBitmap(context:Context,uri:String,allowNetwork:Boolean):Bitmap? = withContext(Dispatchers.IO) {
    val scheme=Uri.parse(uri).scheme?.lowercase()
    val bytes=if(scheme=="http"||scheme=="https") {
        if(!allowNetwork)return@withContext null
        val connection=URI(uri).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=5000;connection.readTimeout=5000
            if(connection.responseCode !in 200..299)return@withContext null
            connection.inputStream.use {boundedBytes(it)}
        } finally {connection.disconnect()}
    } else if(scheme=="asset") {
        val path=Uri.parse(uri).path?.removePrefix("/") ?: return@withContext null
        context.assets.open(path).use { boundedBytes(it) }
    } else context.contentResolver.openInputStream(Uri.parse(uri))?.use{boundedBytes(it)} ?: return@withContext null
    coroutineContext.ensureActive()
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth<=0||bounds.outHeight<=0)return@withContext null
    var sample=1
    while(bounds.outWidth/sample>480||bounds.outHeight/sample>270)sample*=2
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
}
private suspend fun boundedBytes(stream:InputStream):ByteArray {
    val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
    while(true){coroutineContext.ensureActive();val count=stream.read(buffer);if(count<0)break;if(out.size()+count>4*1024*1024)throw IllegalArgumentException("Artwork too large");out.write(buffer,0,count)}
    return out.toByteArray()
}
