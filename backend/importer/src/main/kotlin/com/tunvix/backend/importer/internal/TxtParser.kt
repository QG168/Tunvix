package com.tunvix.backend.importer.internal

/**
 * TXT 频道列表解析。
 *
 * 支持两种常见写法：
 * - `频道名,地址`
 * - `分组名,#genre#`（单独一行的分组声明，作用于其后的频道）
 *
 * 兼容：UTF-8 BOM、CRLF/CR/LF、以 `#` 开头的注释行、制表符分隔、无分组的裸地址行。
 *
 * 暂不支持：`[分组名]` 小节写法、仅有空格分隔的 `频道名 地址`（无法与含空格的频道名区分）。
 */
internal object TxtParser {

    private const val DEFAULT_GROUP = "未分组"
    private val GENRE_LINE = Regex("""^(.*?),\s*#genre#\s*$""", RegexOption.IGNORE_CASE)
    private val BARE_GENRE_LINE = Regex("""^#genre#\s*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): RawParse {
        val channels = ArrayList<ParsedChannel>()
        var group = DEFAULT_GROUP
        var invalidCount = 0

        for (rawLine in TextLines.split(text)) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (BARE_GENRE_LINE.matches(line)) {
                group = DEFAULT_GROUP
                continue
            }
            if (line.startsWith("#")) continue // 注释行

            val genre = GENRE_LINE.matchEntire(line)
            if (genre != null) {
                group = genre.groupValues[1].trim().trim('"', '\'').ifBlank { DEFAULT_GROUP }
                continue
            }

            val parsed = splitChannelLine(line)
            if (parsed == null) {
                invalidCount++
                continue
            }
            channels += ParsedChannel(parsed.first.ifBlank { UrlTools.deriveName(parsed.second) }, parsed.second, group)
        }

        return RawParse(channels = channels, invalidCount = invalidCount)
    }

    /** 供格式嗅探复用：这一行是否可能是 TXT 频道行。 */
    fun looksLikeChannelLine(line: String): Boolean = splitChannelLine(line) != null

    /**
     * `频道名,地址` / `频道名<TAB>地址` / 裸地址。地址里可以带逗号（只按第一个分隔符切分）。
     * 返回 null 表示这一行不是有效的频道行。
     */
    private fun splitChannelLine(line: String): Pair<String, String>? {
        if (UrlTools.isPlayableUrl(line)) return UrlTools.deriveName(line) to line
        val separator = indexOfSeparator(line)
        if (separator < 0) return null
        val name = line.substring(0, separator).trim().trim('"', '\'')
        val url = line.substring(separator + 1).trim()
        if (!UrlTools.isPlayableUrl(url)) return null
        return name to url
    }

    private fun indexOfSeparator(line: String): Int {
        val comma = line.indexOf(',')
        val tab = line.indexOf('\t')
        return when {
            comma >= 0 && tab >= 0 -> minOf(comma, tab)
            comma >= 0 -> comma
            tab >= 0 -> tab
            else -> -1
        }
    }
}
