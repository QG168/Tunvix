package com.tunvix.backend.importer.internal

/**
 * 扩展 M3U/M3U8 解析。
 *
 * 支持：`#EXTM3U`、`#EXTINF`（时长 + 任意顺序属性 + 最后一个逗号后的频道名）、
 * `group-title`、`tvg-logo`、`tvg-id`、`tvg-name`、`http-user-agent`、`http-referrer`/`http-referer`、
 * `#EXTVLCOPT`、`#EXTGRP`。未知属性和未知注释一律忽略，不会让整份文件失败。
 */
internal object M3uParser {

    private const val DEFAULT_GROUP = "未分组"

    private val ATTR_REGEX = Regex("""([A-Za-z0-9_.\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+))""")

    private val MASTER_TAGS = listOf(
        "#EXT-X-STREAM-INF:",
        "#EXT-X-I-FRAME-STREAM-INF:",
        "#EXT-X-MEDIA:",
    )

    private val MEDIA_TAGS = listOf(
        "#EXT-X-TARGETDURATION:",
        "#EXT-X-MEDIA-SEQUENCE:",
        "#EXT-X-PLAYLIST-TYPE:",
        "#EXT-X-ENDLIST",
        "#EXT-X-KEY:",
        "#EXT-X-MAP:",
        "#EXT-X-DISCONTINUITY",
    )

    fun parse(text: String): RawParse {
        val channels = ArrayList<ParsedChannel>()
        val carryHeaders = LinkedHashMap<String, String>()
        var pending: PendingChannel? = null
        var invalidCount = 0
        var titledExtInfCount = 0
        var hasMasterTag = false
        var hasMediaTag = false

        for (rawLine in TextLines.split(text)) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        if (pending != null) invalidCount++ // 上一条 EXTINF 后面没有地址
                        val created = parseExtInf(line)
                        if (created.name.isNotBlank()) titledExtInfCount++
                        // 出现在 EXTINF 之前的 #EXTVLCOPT 归到下一条频道，且优先于 EXTINF 属性。
                        created.vlcHeaders.putAll(carryHeaders)
                        carryHeaders.clear()
                        pending = created
                    }

                    line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                        val (key, value) = parseVlcOption(line) ?: continue
                        if (pending != null) pending.vlcHeaders[key] = value else carryHeaders[key] = value
                    }

                    line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        val group = line.substringAfter(':').trim()
                        if (group.isNotEmpty()) pending?.groupOverride = group
                    }

                    else -> {
                        if (MASTER_TAGS.any { line.startsWith(it, ignoreCase = true) }) hasMasterTag = true
                        if (MEDIA_TAGS.any { line.startsWith(it, ignoreCase = true) }) hasMediaTag = true
                        // 其余注释、未知标记直接忽略
                    }
                }
                continue
            }

            val target = pending
            if (UrlTools.isPlayableUrl(line)) {
                val name = target?.name?.takeIf { it.isNotBlank() } ?: UrlTools.deriveName(line)
                val headers = if (target != null) target.allHeaders() else carryHeaders.toMap()
                channels += ParsedChannel(
                    name = name,
                    url = line,
                    group = target?.groupOverride ?: target?.group ?: DEFAULT_GROUP,
                    logoUrl = target?.logoUrl,
                    externalId = target?.externalId,
                    headers = headers,
                )
            } else {
                invalidCount++
            }
            carryHeaders.clear()
            pending = null
        }
        if (pending != null) invalidCount++

        return RawParse(
            channels = channels,
            invalidCount = invalidCount,
            singleStreamReason = singleStreamReason(hasMasterTag, hasMediaTag, titledExtInfCount),
        )
    }

    /**
     * 单个 HLS 流的播放（分片）清单不能被拆成多个频道，返回提示让用户改用「手动频道」。
     */
    private fun singleStreamReason(hasMasterTag: Boolean, hasMediaTag: Boolean, titledExtInfCount: Int): String? = when {
        hasMasterTag -> "这个地址是同一路直播的主播放列表（多清晰度清单），不是频道列表。"
        hasMediaTag && titledExtInfCount == 0 -> "这个地址是 HLS 分片清单（只有分片地址，没有频道名），不是频道列表。"
        else -> null
    }

    private class PendingChannel(
        val name: String,
        val group: String?,
        val logoUrl: String?,
        val externalId: String?,
        val attrHeaders: Map<String, String>,
    ) {
        val vlcHeaders = LinkedHashMap<String, String>()
        var groupOverride: String? = null

        /** #EXTVLCOPT 的优先级高于 EXTINF 上的同名属性。 */
        fun allHeaders(): Map<String, String> = attrHeaders + vlcHeaders
    }

    /** `#EXTINF:-1 tvg-id="x" group-title="A,B",频道名,带,逗号` */
    private fun parseExtInf(line: String): PendingChannel {
        val body = line.substringAfter(':', "").trim()
        val comma = firstCommaOutsideQuotes(body)
        val attributePart = if (comma >= 0) body.substring(0, comma) else body
        val title = if (comma >= 0) body.substring(comma + 1).trim() else ""
        val attributes = parseAttributes(attributePart)
        val name = title.ifBlank { attributes["tvg-name"].orEmpty().trim() }
        return PendingChannel(
            name = name,
            group = attributes["group-title"]?.trim()?.takeIf { it.isNotEmpty() },
            logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])?.trim()?.takeIf { it.isNotEmpty() },
            externalId = attributes["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() },
            attrHeaders = attributeHeaders(attributes),
        )
    }

    private fun attributeHeaders(attributes: Map<String, String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        (attributes["http-user-agent"] ?: attributes["user-agent"])?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { headers["User-Agent"] = it }
        (attributes["http-referrer"] ?: attributes["http-referer"] ?: attributes["referer"])?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { headers["Referer"] = it }
        return headers
    }

    /** `#EXTVLCOPT:http-user-agent=xxx` / `#EXTVLCOPT:http-referrer=xxx`，其余选项忽略。 */
    private fun parseVlcOption(line: String): Pair<String, String>? {
        val body = line.substringAfter(':', "").trim()
        val key = body.substringBefore('=', "").trim().lowercase()
        val value = body.substringAfter('=', "").trim()
        if (key.isEmpty() || value.isEmpty()) return null
        return when (key) {
            "http-user-agent", "user-agent" -> "User-Agent" to value
            "http-referrer", "http-referer", "referer" -> "Referer" to value
            else -> null
        }
    }

    private fun parseAttributes(text: String): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        for (match in ATTR_REGEX.findAll(text)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }
            attributes[key] = value.trim()
        }
        return attributes
    }

    /** 频道名可能自带逗号，属性值里也可能带逗号，因此只在引号外的第一个逗号处切分。 */
    private fun firstCommaOutsideQuotes(text: String): Int {
        var quote: Char? = null
        for (index in text.indices) {
            val char = text[index]
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == ',' -> return index
            }
        }
        return -1
    }
}
