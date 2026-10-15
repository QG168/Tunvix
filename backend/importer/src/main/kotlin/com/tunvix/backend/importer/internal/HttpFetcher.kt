package com.tunvix.backend.importer.internal

import com.tunvix.backend.importer.ImportErrorCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 订阅地址下载：HttpURLConnection，手动跟随重定向，限制体积与超时，支持协程取消。
 *
 * 非 2xx 一律按错误处理且不读取响应体，因此 404/403 返回的 HTML 不会进入解析流程。
 *
 * 取消语义：把阻塞下载放进可取消的续体，取消时立刻 `disconnect()`，
 * 阻塞中的读会马上抛错退出，不会等到读取超时。取消异常照常向上抛出。
 */
internal class HttpFetcher(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : RemoteFetcher {

    override suspend fun fetch(url: String): ImportedContent {
        val initial = UrlTools.toHttpUrl(url)
            ?: invalidUrl("请输入以 http:// 或 https:// 开头的频道列表地址。")
        val active = AtomicReference<HttpURLConnection?>(null)
        try {
            return withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { runCatching { active.get()?.disconnect() } }
                    try {
                        val content = download(initial, active) { continuation.isActive }
                        if (continuation.isActive) continuation.resume(content)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        } finally {
            runCatching { active.get()?.disconnect() }
        }
    }

    /** 阻塞式下载。取消通过 [isActive] 判定，取消时由外层断开连接。 */
    private fun download(
        initial: URL,
        active: AtomicReference<HttpURLConnection?>,
        isActive: () -> Boolean,
    ): ImportedContent {
        var current = initial
        var redirects = 0
        val visited = LinkedHashSet<String>().apply { add(current.toString()) }
        try {
            while (true) {
                if (!isActive()) throw CancellationException(LimitedReader.CANCELLED_MESSAGE)
                val connection = open(current)
                active.set(connection)
                val redirectTo: String
                try {
                    val status = connection.responseCode
                    when {
                        status in 300..399 -> redirectTo = redirectTarget(current, connection, status)
                        status in 200..299 -> return readBody(connection, current, isActive)
                        else -> throw ImportException(ImportErrorCodes.HTTP_STATUS, httpStatusMessage(status))
                    }
                } finally {
                    connection.disconnect()
                    active.set(null)
                }
                redirects++
                if (redirects > maxRedirects) {
                    throw ImportException(ImportErrorCodes.REDIRECT, "地址重定向次数过多（超过 $maxRedirects 次），已停止。")
                }
                if (!visited.add(redirectTo)) {
                    throw ImportException(ImportErrorCodes.REDIRECT, "地址重定向出现循环，已停止。")
                }
                current = UrlTools.toHttpUrl(redirectTo)
                    ?: throw ImportException(ImportErrorCodes.REDIRECT, "重定向目标不是 http/https 地址，已停止。")
            }
        } catch (e: InterruptedIOException) {
            // 连接超时与读超时都属于 InterruptedIOException。
            throw ImportException(
                ImportErrorCodes.TIMEOUT,
                "请求超时（连接 ${connectTimeoutMs / 1000} 秒 / 读取 ${readTimeoutMs / 1000} 秒），请检查网络后重试。",
            )
        } catch (e: UnknownHostException) {
            throw ImportException(ImportErrorCodes.NETWORK, "无法解析服务器地址，请检查网络连接。")
        } catch (e: IOException) {
            // 取消时连接会被主动断开，这里会先收到 IOException，需要把取消语义还原回去。
            if (!isActive()) throw CancellationException(LimitedReader.CANCELLED_MESSAGE)
            throw ImportException(ImportErrorCodes.NETWORK, "网络请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun open(url: URL): HttpURLConnection {
        val connection = (url.openConnection() as? HttpURLConnection)
            ?: throw ImportException(ImportErrorCodes.INVALID_URL, "只支持 http/https 地址。")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty(
            "Accept",
            "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*",
        )
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "close")
        return connection
    }

    private fun redirectTarget(base: URL, connection: HttpURLConnection, status: Int): String {
        val location = connection.getHeaderField("Location")
        if (location.isNullOrBlank()) {
            throw ImportException(ImportErrorCodes.REDIRECT, "服务器返回 $status 重定向但没有给出目标地址。")
        }
        return UrlTools.resolve(base, location)
            ?: throw ImportException(ImportErrorCodes.REDIRECT, "重定向目标地址无效，已停止。")
    }

    private fun readBody(connection: HttpURLConnection, url: URL, isActive: () -> Boolean): ImportedContent {
        val contentType = connection.contentType
        val declaredCharset = contentType?.let(::parseCharset)
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maxBytes) {
            throw ImportException(
                ImportErrorCodes.TOO_LARGE,
                "列表体积超过 ${maxBytes / 1024 / 1024} MB 上限，已停止导入。",
            )
        }
        val stream = try {
            connection.inputStream
        } catch (e: IOException) {
            if (!isActive()) throw CancellationException(LimitedReader.CANCELLED_MESSAGE)
            throw ImportException(ImportErrorCodes.NETWORK, "读取响应失败：${e.message ?: e.javaClass.simpleName}")
        }
        val bytes = stream.use { LimitedReader.read(it, maxBytes, "返回内容", "服务器返回了空内容。", isActive) }
        return ImportedContent(bytes, declaredCharset, contentType, UrlTools.sanitize(url.toString()))
    }

    private fun httpStatusMessage(status: Int): String = when (status) {
        401 -> "地址需要登录验证（401），无法直接下载频道列表。"
        403 -> "服务器拒绝访问（403），该地址可能已限制外部访问或需要登录。"
        404 -> "地址不存在（404），链接可能已失效，请检查后重试。"
        408 -> "服务器请求超时（408），请稍后重试。"
        429 -> "请求过于频繁（429），请稍后再试。"
        in 500..599 -> "服务器错误（$status），请稍后重试。"
        else -> "服务器返回状态码 $status，没有取得频道列表。"
    }

    internal companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
        const val DEFAULT_MAX_REDIRECTS = 5
        const val DEFAULT_USER_AGENT = "Tunvix/0.1 (Android)"

        fun parseCharset(contentType: String): String? = contentType
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }
    }
}
