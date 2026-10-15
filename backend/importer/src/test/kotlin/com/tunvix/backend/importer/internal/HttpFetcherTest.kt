package com.tunvix.backend.importer.internal

import com.tunvix.backend.importer.ImportErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpFetcherTest {

    private lateinit var server: TestHttpServer

    @Before
    fun setUp() {
        server = TestHttpServer().start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `跟随重定向拿到最终内容`() {
        server.route("/start") { socket, _ -> TestHttpServer.writeRedirect(socket, "/final") }
        server.route("/final") { socket, _ -> TestHttpServer.writeText(socket, "#EXTM3U\n") }

        val content = runBlocking { HttpFetcher().fetch("${server.baseUrl}/start") }

        assertEquals("#EXTM3U\n", String(content.bytes, Charsets.UTF_8))
        assertEquals("/final", content.origin.substringAfter(server.baseUrl))
    }

    @Test
    fun `404 返回状态错误而不是把网页当列表`() {
        server.route("/missing") { socket, _ ->
            TestHttpServer.writeBytes(
                socket,
                404,
                "Not Found",
                "<html><body>404</body></html>".toByteArray(),
                mapOf("Content-Type" to "text/html; charset=utf-8"),
            )
        }

        val error = failureOf { HttpFetcher().fetch("${server.baseUrl}/missing") }

        assertEquals(ImportErrorCodes.HTTP_STATUS, error.code)
        assertTrue(error.message!!.contains("404"))
    }

    @Test
    fun `重定向次数过多会停止`() {
        server.route("/loop") { socket, _ -> TestHttpServer.writeRedirect(socket, "/loop") }

        val error = failureOf { HttpFetcher(maxRedirects = 2).fetch("${server.baseUrl}/loop") }

        assertEquals(ImportErrorCodes.REDIRECT, error.code)
    }

    @Test
    fun `超过体积上限会停止`() {
        server.route("/big") { socket, _ -> TestHttpServer.writeText(socket, "x".repeat(4096)) }

        val error = failureOf { HttpFetcher(maxBytes = 1024).fetch("${server.baseUrl}/big") }

        assertEquals(ImportErrorCodes.TOO_LARGE, error.code)
    }

    @Test
    fun `读取超时返回超时错误`() {
        server.route("/slow") { socket, _ ->
            Thread.sleep(1_500)
            TestHttpServer.writeText(socket, "#EXTM3U\n")
        }

        val error = failureOf {
            HttpFetcher(connectTimeoutMs = 2_000, readTimeoutMs = 200).fetch("${server.baseUrl}/slow")
        }

        assertEquals(ImportErrorCodes.TIMEOUT, error.code)
    }

    @Test
    fun `空响应体返回空内容错误`() {
        server.route("/empty") { socket, _ -> TestHttpServer.writeText(socket, "") }

        val error = failureOf { HttpFetcher().fetch("${server.baseUrl}/empty") }

        assertEquals(ImportErrorCodes.EMPTY_CONTENT, error.code)
    }

    @Test
    fun `响应声明的字符集被带回`() {
        server.route("/gbk") { socket, _ ->
            TestHttpServer.writeText(
                socket,
                "湖南卫视,http://stream.example.invalid/hunan",
                contentType = "text/plain; charset=GBK",
                charset = Charset.forName("GB18030"),
            )
        }

        val content = runBlocking { HttpFetcher().fetch("${server.baseUrl}/gbk") }

        assertEquals("GBK", content.declaredCharset)
        assertEquals(
            "湖南卫视,http://stream.example.invalid/hunan",
            TextDecoder.decode(content.bytes, content.declaredCharset).text,
        )
    }

    @Test
    fun `非 http 地址不会发起请求`() {
        val error = failureOf { HttpFetcher().fetch("ftp://list.example.invalid/tv.m3u") }
        assertEquals(ImportErrorCodes.INVALID_URL, error.code)
    }

    @Test
    fun `取消下载会立刻结束并向上抛出取消`() = runBlocking {
        val accepted = CountDownLatch(1)
        server.route("/hold") { _, _ ->
            accepted.countDown()
            Thread.sleep(10_000)
        }

        val job = launch(Dispatchers.Default) { HttpFetcher().fetch("${server.baseUrl}/hold") }
        assertTrue("服务端没有收到请求", accepted.await(5, TimeUnit.SECONDS))

        try {
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            job.cancel()
        }
    }

    private fun failureOf(block: suspend () -> Any): ImportException {
        val error = runBlocking { runCatching { block() }.exceptionOrNull() }
        assertTrue("期望 ImportException，实际是 $error", error is ImportException)
        return error as ImportException
    }
}
