package com.tunvix.backend.importer.internal

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal data class DecodedText(val text: String, val charset: String)

/**
 * 文本编码识别。顺序：BOM → 响应声明 → UTF-8 严格解码 → GB18030（GBK 超集）→ Latin-1 兜底。
 * 兜底保证任何字节都能解出文本，由上层按"是否解析出频道"决定成败，不在这里抛异常。
 */
internal object TextDecoder {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun decode(bytes: ByteArray, declaredCharset: String? = null): DecodedText {
        bomTag(bytes)?.let { (charset, bomLength) ->
            return DecodedText(decodeLenient(bytes, bomLength, charset), charset)
        }
        charsetOrNull(declaredCharset)?.let { charset ->
            strictDecode(bytes, charset)?.let { return DecodedText(it, charset.name()) }
        }
        val utf8 = charsetOrNull("UTF-8")!!
        strictDecode(bytes, utf8)?.let { return DecodedText(it, utf8.name()) }
        gbCharset()?.let { charset -> strictDecode(bytes, charset)?.let { return DecodedText(it, charset.name()) } }
        val latin1 = charsetOrNull("ISO-8859-1")!!
        return DecodedText(String(bytes, latin1), latin1.name())
    }

    private fun bomTag(bytes: ByteArray): Pair<String, Int>? = when {
        bytes.startsWith(UTF8_BOM) -> "UTF-8" to UTF8_BOM.size
        bytes.startsWith(UTF16LE_BOM) -> "UTF-16LE" to UTF16LE_BOM.size
        bytes.startsWith(UTF16BE_BOM) -> "UTF-16BE" to UTF16BE_BOM.size
        else -> null
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun decodeLenient(bytes: ByteArray, offset: Int, charsetName: String): String {
        val charset = charsetOrNull(charsetName) ?: charsetOrNull("UTF-8")!!
        return String(bytes, offset, bytes.size - offset, charset)
    }

    /** 声明值可能是 "gbk"、"GB2312"、"utf8" 等写法，统一做一次映射与可用性检查。 */
    private fun charsetOrNull(name: String?): Charset? {
        val normalized = name?.trim()?.trim('"', '\'')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val mapped = when (normalized) {
            "utf8", "utf-8", "utf 8" -> "UTF-8"
            "gbk", "gb2312", "gb-2312", "gb18030", "cp936", "ms936" -> "GB18030"
            "utf16", "utf-16", "utf16le", "utf-16le" -> "UTF-16LE"
            "utf16be", "utf-16be" -> "UTF-16BE"
            "latin1", "iso-8859-1", "iso8859-1" -> "ISO-8859-1"
            else -> normalized
        }
        return try {
            Charset.forName(mapped)
        } catch (_: Exception) {
            null
        }
    }

    private fun gbCharset(): Charset? = charsetOrNull("GB18030") ?: charsetOrNull("GBK")

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }
}
