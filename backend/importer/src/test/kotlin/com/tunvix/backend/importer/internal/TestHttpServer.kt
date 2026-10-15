package com.tunvix.backend.importer.internal

import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 单元测试用的极小 HTTP 服务：只实现测试需要的行为（正常响应、重定向、超时、占住连接）。
 * 使用 ServerSocket 手写，避免依赖 JDK 之外的模块。
 */
internal class TestHttpServer {

    private val serverSocket = ServerSocket(0)
    private val routes = LinkedHashMap<String, (Socket, String) -> Unit>()
    private val threads = CopyOnWriteArrayList<Thread>()

    @Volatile
    private var running = false

    val port: Int get() = serverSocket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun route(path: String, handler: (Socket, String) -> Unit): TestHttpServer {
        routes[path] = handler
        return this
    }

    fun start(): TestHttpServer {
        running = true
        val acceptor = Thread { acceptLoop() }
        acceptor.isDaemon = true
        acceptor.start()
        threads += acceptor
        return this
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
        threads.forEach { runCatching { it.interrupt() } }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                return
            }
            val worker = Thread { handle(socket) }
            worker.isDaemon = true
            worker.start()
            threads += worker
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val handler = routes[path.substringBefore('?')]
            if (handler == null) {
                writeBytes(socket, 404, "Not Found", "<html><body>404</body></html>".toByteArray())
            } else {
                handler(socket, path)
            }
        } catch (e: Exception) {
            // 客户端提前断开（例如测试取消下载）时忽略
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        fun writeBytes(
            socket: Socket,
            status: Int,
            reason: String,
            body: ByteArray,
            headers: Map<String, String> = emptyMap(),
        ) {
            val head = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                headers.forEach { (key, value) -> append("$key: $value\r\n") }
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }

        fun writeText(
            socket: Socket,
            body: String,
            contentType: String = "audio/x-mpegurl; charset=utf-8",
            charset: Charset = Charsets.UTF_8,
        ) {
            writeBytes(socket, 200, "OK", body.toByteArray(charset), mapOf("Content-Type" to contentType))
        }

        fun writeRedirect(socket: Socket, location: String) {
            writeBytes(socket, 302, "Found", ByteArray(0), mapOf("Location" to location))
        }
    }
}
