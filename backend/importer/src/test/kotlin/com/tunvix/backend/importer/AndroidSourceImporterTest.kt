package com.tunvix.backend.importer

import com.tunvix.backend.importer.internal.ImportedContent
import com.tunvix.backend.importer.internal.ImportException
import com.tunvix.backend.importer.internal.LocalReader
import com.tunvix.backend.importer.internal.RemoteFetcher
import com.tunvix.contract.ImportReport
import com.tunvix.contract.ImportRequest
import com.tunvix.contract.Outcome
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 入口级测试：用假下载/假读取驱动 [AndroidSourceImporter]，覆盖错误码映射与取消传播。
 * 真实 HTTP 行为由 HttpFetcherTest 用本地服务覆盖。
 */
class AndroidSourceImporterTest {

    private class FakeFetcher(private val content: () -> ImportedContent) : RemoteFetcher {
        var calls = 0
            private set
        var lastUrl: String? = null
            private set

        override suspend fun fetch(url: String): ImportedContent {
            calls++
            lastUrl = url
            return content()
        }
    }

    private class FakeReader(private val content: () -> ImportedContent) : LocalReader {
        var calls = 0
            private set

        override suspend fun read(uri: String): ImportedContent {
            calls++
            return content()
        }
    }

    private class Harness(
        text: String,
        charset: Charset = Charsets.UTF_8,
        declaredCharset: String? = null,
        origin: String = "https://list.example.invalid/tv.m3u",
    ) {
        val fetcher = FakeFetcher { ImportedContent(text.toByteArray(charset), declaredCharset, "text/plain", origin) }
        val reader = FakeReader { ImportedContent(text.toByteArray(charset), declaredCharset, "text/plain", "sample.txt") }
        val importer = AndroidSourceImporter(fetcher, reader)
        val subscription = ImportRequest.Subscription("https://list.example.invalid/tv.m3u?token=SECRET")
        val localFile = ImportRequest.LocalFile("content://test/sample")
    }

    @Test
    fun `导入扩展订阅后报告频道数与分组数`() = runBlocking {
        val harness = Harness(readSample("desensitized-extended.m3u"))

        val report = harness.importer.inspect(harness.subscription).requireSuccess()

        assertEquals(1, harness.fetcher.calls)
        assertEquals(3, report.channels.size)
        assertEquals(2, report.groupCount)
        assertEquals(3, report.skippedCount) // 2 条无效 + 1 条重复
        val first = report.channels.first()
        assertEquals("CCTV-1 综合", first.name)
        assertEquals("央视", first.group)
        assertEquals("cctv1", first.externalId)
        assertEquals("TunvixSample/1.0", first.headers["User-Agent"])
        assertEquals("https://ref.example.invalid/", first.headers["Referer"])
    }

    @Test
    fun `导入 TXT 样本保留分组且没有跳过`() = runBlocking {
        val harness = Harness(readSample("desensitized-plain.txt"))

        val report = harness.importer.inspect(harness.localFile).requireSuccess()

        assertEquals(1, harness.reader.calls)
        assertEquals(4, report.channels.size)
        assertEquals(2, report.groupCount)
        assertEquals(0, report.skippedCount)
        assertEquals(listOf("央视", "央视", "卫视", "卫视"), report.channels.map { it.group })
    }

    @Test
    fun `GBK 编码的本地文件按声明解码`() = runBlocking {
        val harness = Harness(
            "央视,#genre#\r\nCCTV-1 综合,http://stream.example.invalid/cctv1\r\n",
            charset = Charset.forName("GB18030"),
            declaredCharset = "GBK",
        )

        val report = harness.importer.inspect(harness.localFile).requireSuccess()

        assertEquals("CCTV-1 综合", report.channels.single().name)
        assertEquals("央视", report.channels.single().group)
    }

    @Test
    fun `网页错误页不会被当成频道列表`() = runBlocking {
        val harness = Harness(readSample("webpage-error.html"))

        val failure = harness.importer.inspect(harness.subscription).requireFailure()

        assertEquals(ImportErrorCodes.NOT_A_LIST, failure.code)
        assertEquals(1, harness.fetcher.calls)
    }

    @Test
    fun `HLS 分片清单提示改用手动频道`() = runBlocking {
        val harness = Harness(readSample("hls-media-playlist.m3u8"))

        val failure = harness.importer.inspect(harness.subscription).requireFailure()

        assertEquals(ImportErrorCodes.SINGLE_STREAM, failure.code)
        assertTrue(failure.message.contains("手动频道"))
    }

    @Test
    fun `全部条目无效时返回失败而不是空源`() = runBlocking {
        val harness = Harness("#EXTM3U\n#EXTINF:-1,孤儿条目\nudp://@239.0.0.1:1234\n随便一行文字\n")

        val failure = harness.importer.inspect(harness.subscription).requireFailure()

        assertEquals(ImportErrorCodes.NO_VALID_CHANNEL, failure.code)
    }

    @Test
    fun `完全无法识别的文本返回格式错误`() = runBlocking {
        val harness = Harness("这是一段没有任何频道信息的文本\n也没有地址\n")

        val failure = harness.importer.inspect(harness.subscription).requireFailure()

        assertEquals(ImportErrorCodes.NOT_A_LIST, failure.code)
    }

    @Test
    fun `空内容返回空内容错误`() = runBlocking {
        val harness = Harness("   \n\n")

        val failure = harness.importer.inspect(harness.localFile).requireFailure()

        assertEquals(ImportErrorCodes.EMPTY_CONTENT, failure.code)
    }

    @Test
    fun `非法订阅地址不发起请求`() = runBlocking {
        val harness = Harness("#EXTM3U\n")

        val failure = harness.importer.inspect(ImportRequest.Subscription("list.example.invalid/tv.m3u")).requireFailure()

        assertEquals(ImportErrorCodes.INVALID_URL, failure.code)
        assertEquals(0, harness.fetcher.calls)
    }

    @Test
    fun `本地读取失败的错误码原样返回`() = runBlocking {
        val importer = AndroidSourceImporter(
            FakeFetcher { ImportedContent(ByteArray(0), null, null, "unused") },
            FakeReader { throw ImportException(ImportErrorCodes.URI_PERMISSION_DENIED, "该文件的访问授权已失效，请重新选择文件。") },
        )

        val failure = importer.inspect(ImportRequest.LocalFile("content://test/sample")).requireFailure()

        assertEquals(ImportErrorCodes.URI_PERMISSION_DENIED, failure.code)
    }

    @Test
    fun `订阅下载异常被转换成失败而不是崩溃`() = runBlocking {
        val importer = AndroidSourceImporter(
            FakeFetcher { throw ImportException(ImportErrorCodes.NETWORK, "网络请求失败：连接被拒绝") },
            FakeReader { ImportedContent(ByteArray(0), null, null, "unused") },
        )

        val failure = importer.inspect(ImportRequest.Subscription("https://list.example.invalid/tv.m3u")).requireFailure()

        assertEquals(ImportErrorCodes.NETWORK, failure.code)
    }

    @Test
    fun `取消不会被转换成失败结果`() = runBlocking {
        val importer = AndroidSourceImporter(
            object : RemoteFetcher {
                override suspend fun fetch(url: String): ImportedContent = awaitCancellation()
            },
            FakeReader { ImportedContent(ByteArray(0), null, null, "unused") },
        )

        val error = runCatching {
            withTimeout(100) { importer.inspect(ImportRequest.Subscription("https://list.example.invalid/tv.m3u")) }
        }.exceptionOrNull()

        assertTrue("期望取消异常，实际是 $error", error is TimeoutCancellationException)
    }

    private fun readSample(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/samples/$name")) { "缺少测试样本 $name" }
            .use { it.readBytes() }
            .toString(Charsets.UTF_8)

    private fun <T> Outcome<T>.requireSuccess(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError("期望导入成功，实际失败：$code $message")
    }

    private fun <T> Outcome<T>.requireFailure(): Outcome.Failure = when (this) {
        is Outcome.Success -> throw AssertionError("期望导入失败，实际成功：${value}")
        is Outcome.Failure -> this
    }
}
