package com.tunvix.backend.importer

import android.content.Context
import com.tunvix.backend.importer.internal.AndroidLocalReader
import com.tunvix.backend.importer.internal.HttpFetcher
import com.tunvix.backend.importer.internal.ImportException
import com.tunvix.backend.importer.internal.LocalReader
import com.tunvix.backend.importer.internal.ParsedChannel
import com.tunvix.backend.importer.internal.PlaylistFormat
import com.tunvix.backend.importer.internal.PlaylistParser
import com.tunvix.backend.importer.internal.PlaylistSniffer
import com.tunvix.backend.importer.internal.RemoteFetcher
import com.tunvix.backend.importer.internal.TextDecoder
import com.tunvix.backend.importer.internal.UrlTools
import com.tunvix.contract.ChannelDraft
import com.tunvix.contract.ImportReport
import com.tunvix.contract.ImportRequest
import com.tunvix.contract.Outcome
import com.tunvix.contract.SourceImporter
import kotlinx.coroutines.CancellationException

/**
 * 直播源导入模块的公开入口。
 *
 * 只负责"把地址或文件解析成频道草稿"：不写数据库、不生成频道 ID、不访问每个频道验证播放。
 * 网络与文件操作都在 IO 线程执行；失败通过 `Outcome.Failure` 返回可直接展示的中文说明，
 * 协程取消照常向上抛出（不会被吞掉）。
 *
 * 用法：
 * ```
 * val importer: SourceImporter = AndroidSourceImporter(context)
 * when (val result = importer.inspect(ImportRequest.Subscription(url))) {
 *     is Outcome.Success -> result.value.channels   // 交给 CatalogRepository.commitImport
 *     is Outcome.Failure -> showError(result.message, result.code)
 * }
 * ```
 */
class AndroidSourceImporter internal constructor(
    private val fetcher: RemoteFetcher,
    private val localReader: LocalReader,
) : SourceImporter {

    constructor(context: Context) : this(
        HttpFetcher(),
        AndroidLocalReader(context.applicationContext),
    )

    override suspend fun inspect(request: ImportRequest): Outcome<ImportReport> {
        try {
            val content = when (request) {
                is ImportRequest.Subscription -> {
                    if (!UrlTools.isSubscriptionUrl(request.url)) {
                        return Outcome.Failure(
                            "请输入以 http:// 或 https:// 开头的频道列表地址。",
                            ImportErrorCodes.INVALID_URL,
                        )
                    }
                    fetcher.fetch(request.url)
                }

                is ImportRequest.LocalFile -> localReader.read(request.uri)
            }

            val text = TextDecoder.decode(content.bytes, content.declaredCharset).text

            if (text.isBlank()) {
                return Outcome.Failure(
                    "${content.origin} 没有内容，无法导入。",
                    ImportErrorCodes.EMPTY_CONTENT,
                )
            }
            if (PlaylistSniffer.looksLikeWebPage(text)) {
                return Outcome.Failure(
                    "${content.origin} 返回的是网页或数据接口内容，不是频道列表；" +
                        "请确认该地址直接打开时显示的是 M3U/TXT 文本。",
                    ImportErrorCodes.NOT_A_LIST,
                )
            }

            val parsed = PlaylistParser.parse(text)
            return when {
                parsed.singleStreamReason != null -> Outcome.Failure(
                    "${content.origin} ${parsed.singleStreamReason}请改用「手动频道」添加这个播放地址。",
                    ImportErrorCodes.SINGLE_STREAM,
                )

                parsed.channels.isEmpty() && parsed.format == PlaylistFormat.UNKNOWN -> Outcome.Failure(
                    "无法识别列表格式：内容里没有找到频道信息。" +
                        "支持 M3U/M3U8 扩展列表，以及「频道名,地址」的 TXT 列表。",
                    ImportErrorCodes.NOT_A_LIST,
                )

                parsed.channels.isEmpty() -> Outcome.Failure(
                    "没有解析出可用频道，共跳过 ${parsed.skippedCount} 条无效条目，请检查列表内容。",
                    ImportErrorCodes.NO_VALID_CHANNEL,
                )

                else -> Outcome.Success(
                    ImportReport(
                        channels = parsed.channels.map(ParsedChannel::toDraft),
                        skippedCount = parsed.skippedCount,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e // 取消必须继续向上传递，不能被转换成普通失败
        } catch (e: ImportException) {
            return Outcome.Failure(e.message ?: "导入失败。", e.code)
        } catch (e: Throwable) {
            return Outcome.Failure("导入失败：${e.message ?: e.javaClass.simpleName}", ImportErrorCodes.UNKNOWN)
        }
    }
}

private fun ParsedChannel.toDraft(): ChannelDraft = ChannelDraft(
    name = name,
    url = url,
    group = group,
    logoUrl = logoUrl,
    headers = headers,
    externalId = externalId,
)
