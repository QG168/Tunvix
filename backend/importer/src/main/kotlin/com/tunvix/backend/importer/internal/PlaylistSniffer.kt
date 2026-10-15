package com.tunvix.backend.importer.internal

/**
 * 内容嗅探：先判断"像不像网页/JSON"，再判断是哪种列表。
 */
internal object PlaylistSniffer {

    /**
     * 明显的非列表内容（网页、XML、JSON）。用于在解析前给出更准确的提示，
     * 避免把 200 状态码的登录页、错误页当成频道列表。
     */
    fun looksLikeWebPage(text: String): Boolean {
        val head = text.trimStart().take(512).lowercase()
        if (head.isEmpty()) return false
        return head.startsWith("<") ||
            head.startsWith("{") ||
            head.startsWith("[\"") ||
            head.startsWith("[{")
    }

    fun detect(text: String): PlaylistFormat {
        var hasExtM3u = false
        var hasExtInf = false
        var hasPlainUrl = false
        for (rawLine in TextLines.split(text)) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> hasExtM3u = true
                line.startsWith("#EXTINF", ignoreCase = true) -> hasExtInf = true
                !line.startsWith("#") && TxtParser.looksLikeChannelLine(line) -> hasPlainUrl = true
            }
        }
        return when {
            hasExtM3u || hasExtInf -> PlaylistFormat.M3U
            hasPlainUrl -> PlaylistFormat.PLAIN_LIST
            else -> PlaylistFormat.UNKNOWN
        }
    }
}
