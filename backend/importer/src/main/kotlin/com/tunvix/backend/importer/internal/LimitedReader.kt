package com.tunvix.backend.importer.internal

import com.tunvix.backend.importer.ImportErrorCodes
import kotlinx.coroutines.CancellationException

/**
 * 带上限的分块读取。每块之间检查一次是否已取消，保证取消能及时生效。
 *
 * 不用 `ensureActive()`：读取发生在 `suspendCancellableCoroutine` 的阻塞块里，
 * 那里不能调用挂起函数，因此由调用方传入取消判定。
 */
internal object LimitedReader {

    private const val BUFFER_SIZE = 16 * 1024

    fun read(
        input: java.io.InputStream,
        maxBytes: Long,
        label: String,
        emptyMessage: String,
        isActive: () -> Boolean,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(BUFFER_SIZE * 2)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            if (!isActive()) throw CancellationException(CANCELLED_MESSAGE)
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size().toLong() + read > maxBytes) {
                throw ImportException(
                    ImportErrorCodes.TOO_LARGE,
                    "$label 超过 ${maxBytes / 1024 / 1024} MB 上限，已停止导入。",
                )
            }
            out.write(buffer, 0, read)
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) throw ImportException(ImportErrorCodes.EMPTY_CONTENT, emptyMessage)
        return bytes
    }

    const val CANCELLED_MESSAGE = "导入已取消"
}
