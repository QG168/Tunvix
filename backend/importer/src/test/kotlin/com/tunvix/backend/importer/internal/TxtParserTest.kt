package com.tunvix.backend.importer.internal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TxtParserTest {

    private fun parse(text: String) = TxtParser.parse(text)

    @Test
    fun `频道名加地址的最常见写法`() {
        val result = parse(
            """
            CCTV-1 综合,http://stream.example.invalid/cctv1
            湖南卫视,http://stream.example.invalid/hunan.m3u8?token=REDACTED
            """.trimIndent(),
        )
        assertEquals(0, result.invalidCount)
        assertEquals(listOf("CCTV-1 综合", "湖南卫视"), result.channels.map { it.name })
        assertEquals("未分组", result.channels[0].group)
        assertEquals("http://stream.example.invalid/hunan.m3u8?token=REDACTED", result.channels[1].url)
    }

    @Test
    fun `genre 行切换其后的分组`() {
        val result = parse(
            """
            央视,#genre#
            CCTV-1,http://stream.example.invalid/cctv1
            CCTV-2,http://stream.example.invalid/cctv2
            卫视,#genre#
            湖南卫视,http://stream.example.invalid/hunan
            """.trimIndent(),
        )
        assertEquals(3, result.channels.size)
        assertEquals(listOf("央视", "央视", "卫视"), result.channels.map { it.group })
    }

    @Test
    fun `制表符分隔与裸地址行都能解析`() {
        val result = parse(
            """
            频道A	http://stream.example.invalid/a
            http://cdn.example.invalid/live/beta.m3u8
            """.trimIndent(),
        )
        assertEquals(listOf("频道A", "beta"), result.channels.map { it.name })
        assertEquals("未分组", result.channels[1].group)
    }

    @Test
    fun `地址里的逗号被完整保留`() {
        val result = parse("频道A,http://stream.example.invalid/a?list=1,2,3")
        assertEquals("http://stream.example.invalid/a?list=1,2,3", result.channels.single().url)
    }

    @Test
    fun `注释空行与垃圾行被跳过且计入跳过数`() {
        val result = parse(
            """
            # 这是注释
            
            只有名字没有地址
            频道C,javascript:alert(1)
            频道A,这不是地址
            频道B,http://stream.example.invalid/b
            """.trimIndent(),
        )
        assertEquals(1, result.channels.size)
        assertEquals("频道B", result.channels.single().name)
        assertEquals(3, result.invalidCount)
    }

    @Test
    fun `GB18030 字节经解码后中文频道名不乱码`() {
        val bytes = "分组,#genre#\r\n湖南卫视,http://stream.example.invalid/hunan\r\n"
            .toByteArray(Charset.forName("GB18030"))
        val decoded = TextDecoder.decode(bytes)
        assertEquals("GB18030", decoded.charset)

        val result = parse(decoded.text)
        val channel = result.channels.single()
        assertEquals("湖南卫视", channel.name)
        assertEquals("分组", channel.group)
    }

    @Test
    fun `变体写法与空列表不会崩溃`() {
        assertEquals(0, parse("").channels.size)
        assertEquals(0, parse("\uFEFF#EXTM3U\r\n").channels.size)
        // 大小写混写的 #Genre# 也能识别
        assertEquals("体育", parse("体育,#Genre#\r\n频道A,http://stream.example.invalid/a").channels.single().group)
    }
}
