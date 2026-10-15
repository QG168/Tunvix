package com.tunvix.backend.importer.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text)

    @Test
    fun `属性顺序与引号写法任意都能解析`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title=央视 tvg-logo="https://img.example.invalid/cctv1.png" tvg-id=cctv1 tvg-name="CCTV1",CCTV-1 综合
            #EXTVLCOPT:http-user-agent=TunvixTest/1.0
            #EXTVLCOPT:http-referrer=https://ref.example.invalid/
            https://stream.example.invalid/live/cctv1.m3u8?token=REDACTED&t=1
        """.trimIndent()

        val result = parse(text)

        assertEquals(0, result.invalidCount)
        val channel = result.channels.single()
        assertEquals("CCTV-1 综合", channel.name)
        assertEquals("央视", channel.group)
        assertEquals("https://img.example.invalid/cctv1.png", channel.logoUrl)
        assertEquals("cctv1", channel.externalId)
        assertEquals("https://stream.example.invalid/live/cctv1.m3u8?token=REDACTED&t=1", channel.url)
        assertEquals("TunvixTest/1.0", channel.headers["User-Agent"])
        assertEquals("https://ref.example.invalid/", channel.headers["Referer"])
    }

    @Test
    fun `频道名里的逗号被完整保留`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1",CCTV-1,综合频道
            http://stream.example.invalid/cctv1
            """.trimIndent(),
        )
        assertEquals("CCTV-1,综合频道", result.channels.single().name)
    }

    @Test
    fun `属性值里的逗号不影响频道名切分`() {
        val result = parse(
            """
            #EXTINF:-1 group-title="央视,高清" tvg-id="cctv1",CCTV-1
            http://stream.example.invalid/cctv1
            """.trimIndent(),
        )
        val channel = result.channels.single()
        assertEquals("央视,高清", channel.group)
        assertEquals("CCTV-1", channel.name)
    }

    @Test
    fun `未知属性与未知注释被忽略`() {
        val result = parse(
            """
            #EXTM3U url-tvg="https://epg.example.invalid/e.xml"
            # 这是一行中文注释
            #SOMETHING-UNKNOWN:value
            #EXTINF:-1 catchup="default" timeshift="7" tvg-shift=0 tvg-id="a",频道A
            http://stream.example.invalid/a
            """.trimIndent(),
        )
        assertEquals(0, result.invalidCount)
        assertEquals("频道A", result.channels.single().name)
    }

    @Test
    fun `EXTVLCOPT 优先于 EXTINF 上的同名属性`() {
        val result = parse(
            """
            #EXTINF:-1 http-user-agent="FromExtinf" http-referrer="https://extinf.example.invalid/",频道A
            #EXTVLCOPT:http-user-agent=FromVlc
            http://stream.example.invalid/a
            """.trimIndent(),
        )
        val headers = result.channels.single().headers
        assertEquals("FromVlc", headers["User-Agent"])
        assertEquals("https://extinf.example.invalid/", headers["Referer"])
    }

    @Test
    fun `EXTINF 之前的 EXTVLCOPT 作用于下一条频道`() {
        val result = parse(
            """
            #EXTVLCOPT:http-user-agent=FromVlc
            #EXTINF:-1,频道A
            http://stream.example.invalid/a
            #EXTINF:-1,频道B
            http://stream.example.invalid/b
            """.trimIndent(),
        )
        assertEquals("FromVlc", result.channels[0].headers["User-Agent"])
        assertTrue(result.channels[1].headers.isEmpty())
    }

    @Test
    fun `EXTGRP 覆盖 EXTINF 的分组`() {
        val result = parse(
            """
            #EXTINF:-1 group-title="旧分组",频道A
            #EXTGRP:卫视频道
            http://stream.example.invalid/a
            """.trimIndent(),
        )
        assertEquals("卫视频道", result.channels.single().group)
    }

    @Test
    fun `无后缀与非媒体后缀地址都被接受并保留完整查询参数`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1,无后缀台
            http://stream.example.invalid/live/stream?id=1&type=hls
            #EXTINF:-1,php 台
            http://stream.example.invalid/tv.php?channel=2&sig=REDACTED
            """.trimIndent(),
        )
        assertEquals(0, result.invalidCount)
        assertEquals(
            listOf(
                "http://stream.example.invalid/live/stream?id=1&type=hls",
                "http://stream.example.invalid/tv.php?channel=2&sig=REDACTED",
            ),
            result.channels.map { it.url },
        )
    }

    @Test
    fun `非 http 地址与缺少地址的条目计入跳过`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1,孤儿频道
            #EXTINF:-1,组播地址
            udp://@239.0.0.1:1234
            #EXTINF:-1,rtmp 地址
            rtmp://stream.example.invalid/live/a
            #EXTINF:-1,正常频道
            http://stream.example.invalid/a
            """.trimIndent(),
        )
        assertEquals(1, result.channels.size)
        assertEquals("正常频道", result.channels.single().name)
        assertEquals(3, result.invalidCount)
    }

    @Test
    fun `没有频道名的裸地址行按地址推断名称`() {
        val result = parse(
            """
            #EXTM3U
            http://cdn.example.invalid/live/alpha.m3u8
            https://cdn.example.invalid/live/beta
            """.trimIndent(),
        )
        assertEquals(listOf("alpha", "beta"), result.channels.map { it.name })
        assertEquals(listOf("未分组", "未分组"), result.channels.map { it.group })
    }

    @Test
    fun `CRLF 与 UTF-8 BOM 样本可解析`() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:-1,央视一台\r\nhttp://stream.example.invalid/a\r\n"
        val decoded = TextDecoder.decode(text.toByteArray(Charsets.UTF_8))
        val result = parse(decoded.text)
        assertEquals("央视一台", result.channels.single().name)
        assertEquals("UTF-8", decoded.charset)
    }

    @Test
    fun `主播放列表被识别为单流而不是频道列表`() {
        val result = parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            http://cdn.example.invalid/live/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            http://cdn.example.invalid/live/480p.m3u8
            """.trimIndent(),
        )
        assertTrue(result.singleStreamReason != null)
    }

    @Test
    fun `HLS 分片清单被识别为单流而不是频道列表`() {
        val result = parse(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:120
            #EXTINF:6.000,
            segment-120.ts
            #EXTINF:6.000,
            segment-121.ts
            """.trimIndent(),
        )
        assertTrue(result.singleStreamReason != null)
        assertTrue(result.channels.isEmpty())
    }

    @Test
    fun `带频道名的列表不会被误判为单流`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1,频道A
            http://stream.example.invalid/a.m3u8
            #EXTINF:-1,频道B
            http://stream.example.invalid/b.m3u8
            """.trimIndent(),
        )
        assertNull(result.singleStreamReason)
        assertEquals(2, result.channels.size)
    }
}
