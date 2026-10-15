package com.tunvix.backend.importer.internal

/**
 * 列表解析总入口：识别格式 → 交给对应解析器 → 去重 → 截断到上限。
 *
 * 不做任何网络访问，也不会去验证每个频道能否播放（由播放模块负责）。
 */
internal object PlaylistParser {

    /** 单次导入的频道上限，超出部分计入跳过数。 */
    const val MAX_CHANNELS = 20_000

    fun parse(text: String, maxChannels: Int = MAX_CHANNELS): ParseOutcome {
        val format = PlaylistSniffer.detect(text)
        val raw = when (format) {
            PlaylistFormat.M3U -> M3uParser.parse(text)
            PlaylistFormat.PLAIN_LIST -> TxtParser.parse(text)
            PlaylistFormat.UNKNOWN -> RawParse()
        }

        val unique = LinkedHashMap<String, ParsedChannel>()
        var duplicateCount = 0
        for (channel in raw.channels) {
            val key = dedupeKey(channel)
            if (unique.putIfAbsent(key, channel) != null) duplicateCount++
        }

        val deduped = unique.values.toList()
        val kept = if (deduped.size > maxChannels) deduped.take(maxChannels) else deduped

        return ParseOutcome(
            channels = kept,
            invalidCount = raw.invalidCount,
            duplicateCount = duplicateCount,
            overflowCount = deduped.size - kept.size,
            format = format,
            singleStreamReason = raw.singleStreamReason,
        )
    }

    /** 只有"名称 + 完整地址"都相同才视为重复；同名不同地址、同地址不同名称都保留。 */
    private fun dedupeKey(channel: ParsedChannel): String =
        channel.name.trim().lowercase() + '\u0000' + channel.url.trim()
}
