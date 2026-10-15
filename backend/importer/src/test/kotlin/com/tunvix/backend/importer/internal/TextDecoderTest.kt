package com.tunvix.backend.importer.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextDecoderTest {

    private val gb18030 = Charset.forName("GB18030")

    @Test
    fun `无 BOM 的 UTF-8 中文正常解码`() {
        val decoded = TextDecoder.decode("央视,#genre#\r\n".toByteArray(Charsets.UTF_8))
        assertEquals("央视,#genre#\r\n", decoded.text)
        assertEquals("UTF-8", decoded.charset)
    }

    @Test
    fun `UTF-8 BOM 被去掉`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U\n".toByteArray()
        val decoded = TextDecoder.decode(bytes)
        assertEquals("#EXTM3U\n", decoded.text)
        assertEquals("UTF-8", decoded.charset)
    }

    @Test
    fun `UTF-16LE BOM 被识别`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "频道A".toByteArray(Charset.forName("UTF-16LE"))
        val decoded = TextDecoder.decode(bytes)
        assertEquals("频道A", decoded.text)
        assertEquals("UTF-16LE", decoded.charset)
    }

    @Test
    fun `UTF-8 失败时回退 GB18030`() {
        val decoded = TextDecoder.decode("湖南卫视,http://a.example.invalid/x".toByteArray(gb18030))
        assertEquals("湖南卫视,http://a.example.invalid/x", decoded.text)
        assertEquals("GB18030", decoded.charset)
    }

    @Test
    fun `响应声明的编码优先于自动识别`() {
        val decoded = TextDecoder.decode("央视".toByteArray(gb18030), declaredCharset = "gbk")
        assertEquals("央视", decoded.text)
        assertEquals("GB18030", decoded.charset)
    }

    @Test
    fun `无法识别的字节不会抛异常`() {
        val bytes = byteArrayOf(0x81.toByte(), 0x40, 0xFE.toByte(), 0x7F)
        val decoded = TextDecoder.decode(bytes)
        assertTrue(decoded.charset.isNotEmpty())
    }

    @Test
    fun `三种换行写法都能切分成同样的行`() {
        val crlf = TextLines.split("#EXTM3U\r\nA\r\nB\r\n")
        val lf = TextLines.split("#EXTM3U\nA\nB\n")
        val cr = TextLines.split("#EXTM3U\rA\rB\r")
        assertEquals(listOf("#EXTM3U", "A", "B"), crlf)
        assertEquals(crlf, lf)
        assertEquals(crlf, cr)
    }
}
