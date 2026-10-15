package com.tunvix.backend.importer

/**
 * `Outcome.Failure.code` 的取值集合。
 *
 * 约定：`message` 始终是可以直接展示给用户的中文说明；`code` 供界面做逻辑分支或埋点，
 * 不需要界面理解具体文案。错误信息不回显 URL 的查询参数（可能包含 token）。
 */
object ImportErrorCodes {
    /** 地址不是合法的 http/https 完整地址，请求未发出。 */
    const val INVALID_URL = "IMPORT_INVALID_URL"
    /** DNS 解析失败、连接被拒绝、TLS 失败等网络层错误。 */
    const val NETWORK = "IMPORT_NETWORK"
    /** 连接或读取超时。 */
    const val TIMEOUT = "IMPORT_TIMEOUT"
    /** 服务器返回非 2xx 状态码（404、403、5xx 等）。 */
    const val HTTP_STATUS = "IMPORT_HTTP_STATUS"
    /** 重定向次数过多、缺少重定向目标或出现循环。 */
    const val REDIRECT = "IMPORT_REDIRECT"
    /** 内容超过体积上限。 */
    const val TOO_LARGE = "IMPORT_TOO_LARGE"
    /** 下载或读取到的内容为空。 */
    const val EMPTY_CONTENT = "IMPORT_EMPTY_CONTENT"
    /** 返回的是网页、JSON 等非列表内容。 */
    const val NOT_A_LIST = "IMPORT_NOT_A_LIST"
    /** 内容是单个直播流的播放（分片）清单，应改用「手动频道」添加。 */
    const val SINGLE_STREAM = "IMPORT_SINGLE_STREAM"
    /** 解析失败或全部条目无效，没有可用频道。 */
    const val NO_VALID_CHANNEL = "IMPORT_NO_VALID_CHANNEL"
    /** 本地地址不是 content:// 形式（未经系统文件选择器授权）。 */
    const val URI_NOT_ALLOWED = "IMPORT_URI_NOT_ALLOWED"
    /** 本地 URI 授权已失效。 */
    const val URI_PERMISSION_DENIED = "IMPORT_URI_PERMISSION_DENIED"
    /** 本地文件不存在或无法打开。 */
    const val FILE_UNAVAILABLE = "IMPORT_FILE_UNAVAILABLE"
    /** 未预期的内部错误（兜底）。 */
    const val UNKNOWN = "IMPORT_UNKNOWN"
}
