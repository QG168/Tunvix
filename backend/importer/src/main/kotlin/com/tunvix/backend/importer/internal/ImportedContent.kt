package com.tunvix.backend.importer.internal

/**
 * 一次下载或本地读取的结果。
 *
 * [origin] 已脱敏（去掉查询参数），只用于错误提示，不要在日志里输出原始地址。
 */
internal class ImportedContent(
    val bytes: ByteArray,
    val declaredCharset: String?,
    val contentType: String?,
    val origin: String,
)

/** 订阅地址下载。 */
internal interface RemoteFetcher {
    suspend fun fetch(url: String): ImportedContent
}

/** 系统文件选择器给出的本地 URI 读取。 */
internal interface LocalReader {
    suspend fun read(uri: String): ImportedContent
}
