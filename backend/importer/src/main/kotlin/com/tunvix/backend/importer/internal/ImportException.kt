package com.tunvix.backend.importer.internal

import com.tunvix.backend.importer.ImportErrorCodes

/**
 * 模块内部的失败信号。只在模块内抛出，由 [com.tunvix.backend.importer.AndroidSourceImporter]
 * 统一转换成 `Outcome.Failure`，因此不会被上层当作崩溃处理。
 */
internal class ImportException(
    val code: String,
    message: String,
) : Exception(message)

internal fun invalidUrl(message: String = "请输入以 http:// 或 https:// 开头的完整地址。"): Nothing =
    throw ImportException(ImportErrorCodes.INVALID_URL, message)
