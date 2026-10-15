package com.tunvix.backend.importer.internal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tunvix.backend.importer.ImportErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 本地 URI 的准入规则。纯字符串判断，便于单元测试（不需要 Android 运行时）。
 */
internal object LocalUriRules {
    private const val CONTENT_SCHEME = "content://"

    /**
     * 只接受系统文件选择器返回的 content:// 地址：
     * 不使用全盘存储权限，也不允许直接读任意文件路径。
     */
    fun requireContentUri(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith(CONTENT_SCHEME, ignoreCase = true)) {
            throw ImportException(
                ImportErrorCodes.URI_NOT_ALLOWED,
                "只能导入通过系统文件选择器选择的文件，请重新选择本地文件。",
            )
        }
        return value
    }
}

/**
 * 通过 ContentResolver 读取系统选择的文件，不申请存储权限。
 */
internal class AndroidLocalReader(private val context: Context) : LocalReader {

    override suspend fun read(uri: String): ImportedContent = withContext(Dispatchers.IO) {
        val spec = LocalUriRules.requireContentUri(uri)
        val target = Uri.parse(spec)
        val resolver = context.contentResolver
        val label = displayName(resolver, target)
        val stream = try {
            resolver.openInputStream(target)
                ?: throw ImportException(ImportErrorCodes.FILE_UNAVAILABLE, "无法打开文件：$label")
        } catch (e: SecurityException) {
            throw ImportException(
                ImportErrorCodes.URI_PERMISSION_DENIED,
                "该文件的访问授权已失效，请重新选择文件。",
            )
        } catch (e: FileNotFoundException) {
            throw ImportException(ImportErrorCodes.FILE_UNAVAILABLE, "文件不存在或已被移动：$label")
        }
        val context = currentCoroutineContext()
        val bytes = try {
            stream.use {
                LimitedReader.read(it, MAX_BYTES, "文件", "文件内容为空：$label") { context.isActive }
            }
        } catch (e: IOException) {
            throw ImportException(
                ImportErrorCodes.FILE_UNAVAILABLE,
                "读取文件失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
        ImportedContent(bytes, declaredCharset = null, contentType = resolver.getType(target), origin = label)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val queried = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "所选文件"
    }

    internal companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}
