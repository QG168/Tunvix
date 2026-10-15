package com.tunvix.backend.importer.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParserTest {

    private val m3u = """
        #EXTM3U
        #EXTINF:-1,频道A
        http://stream.example.invalid/a
        #EXTINF:-1,频道A
        http://stream.example.invalid/a
        #EXTINF:-1,频道B
        http://stream.example.invalid/b
        #EXTINF:-1,频道A
        http://stream.example.invalid/c
    """.trimIndent()

    @Test
    fun `顺序保持且完全重复的条目被去重`() {
        val result = PlaylistParser.parse(m3u)
        assertEquals(listOf("频道A", "频道B", "频道A"), result.channels.map { it.name })
        assertEquals(listOf("a", "b", "c"), result.channels.map { it.url.substringAfterLast('/') })
        assertEquals(1, result.duplicateCount)
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun `超出上限的频道计入跳过数`() {
        val result = PlaylistParser.parse(m3u, maxChannels = 2)
        assertEquals(2, result.channels.size)
        assertEquals(1, result.overflowCount)
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun `格式识别与单流提示`() {
        assertEquals(PlaylistFormat.M3U, PlaylistParser.parse(m3u).format)
        assertEquals(PlaylistFormat.PLAIN_LIST, PlaylistParser.parse("频道A,http://a.example.invalid/a").format)
        assertEquals(PlaylistFormat.UNKNOWN, PlaylistParser.parse("这不是列表").format)
        assertNull(PlaylistParser.parse(m3u).singleStreamReason)
        assertTrue(
            PlaylistParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg-1.ts")
                .singleStreamReason != null,
        )
    }

    @Test
    fun `网页与 JSON 内容会被提前识别为非列表`() {
        assertTrue(PlaylistSniffer.looksLikeWebPage("<html><body>403</body></html>"))
        assertTrue(PlaylistSniffer.looksLikeWebPage("<!DOCTYPE html>\n<html>"))
        assertTrue(PlaylistSniffer.looksLikeWebPage("{\"code\":403,\"msg\":\"denied\"}"))
        assertTrue(PlaylistSniffer.looksLikeWebPage("[{\"name\":\"a\"}]"))
        assertTrue(!PlaylistSniffer.looksLikeWebPage("#EXTM3U\nCCTV-1,http://a.example.invalid/a"))
    }
}

class UrlToolsTest {

    @Test
    fun `只有 http 与 https 地址可用`() {
        assertTrue(UrlTools.isPlayableUrl("http://a.example.invalid/live"))
        assertTrue(UrlTools.isPlayableUrl("HTTPS://a.example.invalid/live?id=1"))
        assertTrue(!UrlTools.isPlayableUrl("rtmp://a.example.invalid/live"))
        assertTrue(!UrlTools.isPlayableUrl("udp://@239.0.0.1:1234"))
        assertTrue(!UrlTools.isPlayableUrl("file:///tmp/list.m3u"))
        assertTrue(!UrlTools.isPlayableUrl("http://a.example.invalid/a b.m3u8"))
        assertTrue(!UrlTools.isPlayableUrl(""))
    }

    @Test
    fun `名称推断去掉查询参数与后缀`() {
        assertEquals("cctv1", UrlTools.deriveName("https://cdn.example.invalid/live/cctv1.m3u8?token=REDACTED"))
        assertEquals("beta", UrlTools.deriveName("http://cdn.example.invalid/live/beta"))
        assertEquals("cdn.example.invalid", UrlTools.deriveName("http://cdn.example.invalid"))
    }

    @Test
    fun `错误提示里的地址会去掉查询参数`() {
        assertEquals(
            "https://list.example.invalid/tv.m3u",
            UrlTools.sanitize("https://list.example.invalid/tv.m3u?token=SECRET&user=1"),
        )
    }
}

class LocalUriRulesTest {

    @Test
    fun `只接受系统文件选择器给出的 content 地址`() {
        assertEquals(
            "content://com.android.providers.downloads.documents/document/12",
            LocalUriRules.requireContentUri(" content://com.android.providers.downloads.documents/document/12 "),
        )
        listOf(
            "/sdcard/list.m3u",
            "file:///sdcard/list.m3u",
            "http://a.example.invalid/list.m3u",
            "",
        ).forEach { raw ->
            val error = runCatching { LocalUriRules.requireContentUri(raw) }.exceptionOrNull()
            assertTrue("$raw 应被拒绝", error is ImportException)
        }
    }
}
