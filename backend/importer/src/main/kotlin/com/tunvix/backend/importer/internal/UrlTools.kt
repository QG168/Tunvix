package com.tunvix.backend.importer.internal

import java.net.MalformedURLException
import java.net.URI
import java.net.URL

/**
 * 地址判定的纯函数集合。不使用 Android API，便于单元测试直接覆盖。
 */
internal object UrlTools {

    private val HTTP_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /** 是否为可直接播放/下载的 http(s) 地址。不要求带媒体后缀。 */
    fun isPlayableUrl(candidate: String): Boolean = HTTP_URL.matches(candidate.trim())

    /** 是否为可订阅的列表地址（与频道地址同规则，单独命名便于表达意图）。 */
    fun isSubscriptionUrl(candidate: String): Boolean = isPlayableUrl(candidate)

    /** 解析 http(s) 地址；非 http(s) 或无法解析时返回 null。 */
    @Suppress("DEPRECATION")
    fun toHttpUrl(source: String): URL? {
        val value = source.trim()
        if (!isPlayableUrl(value)) return null
        return try {
            URI(value).toURL()
        } catch (_: Exception) {
            // 部分列表地址含中文字符或空格，URI 过于严格，退回 URL 构造函数解析。
            try {
                URL(value)
            } catch (_: MalformedURLException) {
                null
            }
        }
    }

    /** 相对重定向地址解析。 */
    @Suppress("DEPRECATION")
    fun resolve(base: URL, location: String): String? =
        try {
            URL(base, location.trim()).toString()
        } catch (_: MalformedURLException) {
            null
        }

    /**
     * 从地址推断频道名：优先用路径最后一段（去掉后缀），否则用主机名。
     * 只在列表里确实没有频道名时使用。
     */
    fun deriveName(url: String): String {
        val withoutFragment = url.trim().substringBefore('#').substringBefore('?')
        val afterScheme = withoutFragment.substringAfter("//", withoutFragment)
        val authority = afterScheme.substringBefore('/')
        val host = authority.substringBeforeLast(':', authority).ifBlank { authority }
        val path = afterScheme.substringAfter('/', "")
        val segment = path.trimEnd('/').substringAfterLast('/').trim()
        val name = if (segment.isBlank()) host else segment.substringBeforeLast('.', segment).ifBlank { segment }
        return name.ifBlank { "未命名频道" }
    }

    /**
     * 用于错误提示的脱敏地址：去掉查询参数与片段（可能带 token），并做长度限制。
     */
    fun sanitize(raw: String): String {
        val cut = raw.trim().substringBefore('#').substringBefore('?')
        return if (cut.length <= 96) cut else cut.take(93) + "..."
    }
}
