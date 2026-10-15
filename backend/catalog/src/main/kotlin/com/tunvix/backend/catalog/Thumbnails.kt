package com.tunvix.backend.catalog

import com.tunvix.contract.Channel
import java.io.File

/** Cache and scheduling ceilings. Documented in docs/backend-catalog.md. */
internal object ThumbnailLimits {
    /** Decoder/network budget shared by automatic and manual capture. */
    const val MAX_CONCURRENT = 3
    /** Entire attempt, including format fallback; queue waiting does not consume this budget. */
    const val EXTRACT_TIMEOUT_MS = 5_000L
    /** After a failure the same still is not retried before this window elapses. */
    const val RETRY_COOLDOWN_MS = 10 * 60 * 1000L
    const val MAX_COOLDOWN_ENTRIES = 512
}

/** Cancellable platform frame capture. Production releases its silent player and output surface on cancellation. */
internal interface ThumbnailSource {
    /** Returns bytes==null when the platform cannot capture a frame for this channel. */
    suspend fun fetch(channel: Channel): CaptureResult
}

/**
 * One capture attempt outcome. [firstFrameMillis] is the monotonic measurement from issuing the
 * media request until the first rendered frame (connection + read + decode), excluding queue
 * waiting and JPEG compression; null when no frame was produced.
 */
internal class CaptureResult(val bytes: ByteArray?, val firstFrameMillis: Long? = null)

/** Persisted stills: only replacement, source/channel deletion or explicit clearing removes them. */
internal class ThumbnailStore(
    private val directory: File,
    private val legacyDirectory: File? = null,
) {
    private val files = LinkedHashMap<String, File>()
    private var bytes = 0L

    /** Called by the repository's IO initializer, never by the constructor. */
    @Synchronized
    fun initialize() {
        files.clear()
        bytes = 0L
        directory.mkdirs()
        legacyDirectory?.takeIf { it != directory && it.isDirectory }?.listFiles()?.forEach { old ->
            if (old.isFile && !old.name.endsWith(".tmp")) {
                val target = File(directory, old.name)
                if (!target.exists()) {
                    val temporary = File(directory, old.name + ".tmp")
                    try {
                        old.copyTo(temporary, overwrite = true)
                        if(!temporary.renameTo(target))throw java.io.IOException("Could not migrate thumbnail")
                    } catch (_: java.io.IOException) {
                        // A full disk must not make the catalog unusable. Retry migration next startup.
                    } finally { temporary.delete() }
                }
                if (target.isFile) old.delete()
                if(old.isFile) {
                    files[old.name]=old
                    bytes+=old.length()
                }
            }
        }
        if (directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp")) { file.delete(); return@forEach }
                if (file.isFile) {
                    files.put(file.name,file)?.let { bytes-=it.length() }
                    bytes += file.length()
                }
            }
        }
    }

    @Synchronized
    fun uriOf(name: String?): String? {
        if (name == null) return null
        val file = files[name] ?: return null
        return "file://" + file.absolutePath
    }

    @Synchronized
    fun write(name: String, data: ByteArray): String {
        directory.mkdirs()
        files.remove(name)?.let { bytes -= it.length() }
        val file = File(directory, name)
        try {
            file.writeBytes(data)
        } catch (error: Throwable) {
            // Leave no half-written still behind; the cache index must match what is on disk.
            runCatching { file.delete() }
            throw error
        }
        files[name] = file
        bytes += data.size
        return "file://" + file.absolutePath
    }

    @Synchronized
    fun delete(name: String?) {
        if (name == null) return
        val file = files.remove(name) ?: return
        bytes -= file.length()
        runCatching { file.delete() }
    }

    @Synchronized
    fun sizeBytes(): Long = bytes

    /** Drops every still. Never touches catalog data — personal records stay intact. */
    @Synchronized
    fun clear() {
        files.values.forEach { runCatching { it.delete() } }
        files.clear()
        bytes = 0
    }

    @Synchronized
    fun retain(names: Set<String>) {
        files.keys.filterNot { it in names }.forEach { delete(it) }
    }

    companion object {
        fun fileName(channelId: String, fingerprint: String) = "$channelId-$fingerprint.jpg"
    }
}
