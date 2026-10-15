package com.tunvix.app

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList

/** Only in the test APK: deterministic playlist + generated video, no public live streams. */
class FixtureServer(private val video: ByteArray) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()
    @Volatile var failPlaylist = false
    val requests = CopyOnWriteArrayList<Map<String, String>>()
    val base get() = "http://127.0.0.1:${socket.localPort}"
    init {
        pool.execute { while (!socket.isClosed) {
            try { val client = socket.accept(); pool.execute { serve(client) } }
            catch (_: Exception) { break }
        } }
    }
    private fun serve(client: Socket) = client.use {
        it.soTimeout = 5000
        val reader = it.getInputStream().bufferedReader()
        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
        val headers = linkedMapOf<String, String>("path" to path)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        requests += headers
        val isPlaylist = path.startsWith("/list")
        val bytes = if (isPlaylist) ("#EXTM3U\n#EXTINF:-1 tvg-id=\"fixture\" group-title=\"测试\",测试画面\n#EXTVLCOPT:http-referrer=$base/ref\n$base/stream\n").toByteArray() else video
        val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
        val start = (range ?: 0).coerceIn(0, bytes.lastIndex)
        val status = if (isPlaylist && failPlaylist) "503 Unavailable" else if (range != null) "206 Partial Content" else "200 OK"
        val body = if (status.startsWith("503")) "unavailable".toByteArray() else bytes.copyOfRange(start, bytes.size)
        val output = it.getOutputStream()
        val prefix = buildString {
            append("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n")
            append("Content-Type: ${if (isPlaylist) "audio/x-mpegurl" else "video/mp4"}\r\n")
            if (range != null) append("Content-Range: bytes $start-${bytes.lastIndex}/${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(prefix.toByteArray()); output.write(body); output.flush()
    }
    override fun close() { socket.close(); pool.shutdownNow() }
}
