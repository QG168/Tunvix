package com.tunvix.backend.importer.internal

/** 解析出的频道草稿，字段与契约 `ChannelDraft` 一一对应。 */
internal data class ParsedChannel(
    val name: String,
    val url: String,
    val group: String,
    val logoUrl: String? = null,
    val externalId: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal enum class PlaylistFormat {
    /** 带 `#EXTM3U`/`#EXTINF` 的扩展 M3U。 */
    M3U,

    /** 逐行地址列表（TXT 或裸地址清单）。 */
    PLAIN_LIST,

    /** 没有可识别的频道信息。 */
    UNKNOWN,
}

/** 单次解析的原始结果，去重与截断由 [PlaylistParser] 统一处理。 */
internal data class RawParse(
    val channels: List<ParsedChannel> = emptyList(),
    val invalidCount: Int = 0,
    val singleStreamReason: String? = null,
)

/**
 * 送给导入入口的最终解析结果。
 *
 * [skippedCount] 汇总了未导入的全部条目：无效条目、重复条目、超出上限的条目。
 */
internal data class ParseOutcome(
    val channels: List<ParsedChannel>,
    val invalidCount: Int,
    val duplicateCount: Int,
    val overflowCount: Int,
    val format: PlaylistFormat,
    val singleStreamReason: String?,
) {
    val skippedCount: Int get() = invalidCount + duplicateCount + overflowCount
}
