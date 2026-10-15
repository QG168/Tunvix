package com.tunvix.backend.importer.internal

/**
 * 按行切分文本，统一处理 CRLF / CR / LF 三种换行写法。
 *
 * 不用 `String.splitLines()`：Android 侧编译时该扩展不可用，
 * 自己实现也便于明确"尾部空行不算一行"的行为。
 */
internal object TextLines {
    fun split(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\n' || char == '\r') {
                lines += text.substring(start, index)
                if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                start = index + 1
            }
            index++
        }
        if (start < text.length) lines += text.substring(start)
        return lines
    }
}
