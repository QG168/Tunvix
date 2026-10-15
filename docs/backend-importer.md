# 后端模块交付：直播源导入（backend/importer）

| 项目 | 内容 |
| --- | --- |
| 负责人 | 对话 A（导入） |
| 交付日期 | 2026-10-07 |
| 可写范围 | `backend/importer/`、`docs/backend-importer.md`（未改动 app、根构建、公共契约、其他模块） |
| 依据 | `docs/Tunvix-PRD.md`、`docs/并行开发与后端任务.md`、`core/contract/.../Contracts.kt` |
| 状态 | 主源码编译通过；57 个单元测试全部通过 |

## 1. 公开入口

```kotlin
package com.tunvix.backend.importer

class AndroidSourceImporter(context: Context) : SourceImporter {
    override suspend fun inspect(request: ImportRequest): Outcome<ImportReport>
}
```

- 只实现契约里的 `SourceImporter`，输入 `ImportRequest.Subscription(url)` / `ImportRequest.LocalFile(uri)`，
  输出 `Outcome.Success(ImportReport(channels, skippedCount))` 或 `Outcome.Failure(message, code)`。
- **不负责**：写数据库、生成频道 ID、判断重复源、决定源名称（本地文件的默认源名由前端用 `OpenableColumns.DISPLAY_NAME` 取得）。
- `ImportReport.groupCount` 由契约计算（`channels` 中分组去重数量）。
- 所有网络/文件 IO 都在 `Dispatchers.IO` 上执行；`message` 是可直接展示的中文，`code` 见第 6 节。
- **协程取消会继续向上抛出**（`CancellationException` 不转换成 `Failure`），调用方按正常取消处理即可。

典型用法：

```kotlin
val importer: SourceImporter = AndroidSourceImporter(context)
when (val result = importer.inspect(ImportRequest.Subscription(url))) {
    is Outcome.Success -> {
        // 展示"解析到 N 个频道 / M 个分组，跳过 K 条"，用户确认后：
        catalog.commitImport(name = 源名称, request = request, report = result.value)
    }
    is Outcome.Failure -> showError(result.message, result.code)
}
```

## 2. 依赖与构建

- 模块：Android library，`compileSdk = 37`、`minSdk = 29`，`namespace = com.tunvix.backend.importer`。
- 依赖：`api(project(":core:contract"))`（实现契约接口，故用 `api` 暴露给 app 侧）。
  不引入 okhttp 等第三方库，网络用 `HttpURLConnection`，与根工程工具链保持一致。
- 未编辑 `settings.gradle.kts`：根工程已按 `backend/<name>/build.gradle.kts` 是否存在自动 include。
- 清单只声明 `android.permission.INTERNET`；**不申请任何存储权限**。

## 3. 支持的格式

### 3.1 扩展 M3U / M3U8

| 内容 | 处理方式 |
| --- | --- |
| `#EXTM3U` | 识别列表类型 |
| `#EXTINF:<时长> 属性,频道名` | 属性顺序任意；带引号/不带引号都支持；频道名取**引号外的第一个逗号**之后全部内容（频道名自带逗号、属性值带逗号都不会错切） |
| `group-title` | 分组；缺省为「未分组」 |
| `tvg-logo` / `logo` | 台标地址 |
| `tvg-id` | 频道标识 → `ChannelDraft.externalId` |
| `tvg-name` | 仅在没有频道名时作为兜底名称 |
| `http-user-agent` / `user-agent` | → `headers["User-Agent"]` |
| `http-referrer` / `http-referer` / `referer` | → `headers["Referer"]` |
| `#EXTVLCOPT:http-user-agent=…` / `…:http-referrer=…` | → 同上；**优先级高于 EXTINF 上的同名属性**；写在该频道 `#EXTINF` 之前也会作用于该频道 |
| `#EXTGRP:分组` | 覆盖 `group-title` |
| 其他 `#` 注释与未知标记 | 直接忽略，不会导致整份文件失败 |
| 无 `#EXTINF` 的裸地址行 | 按地址推断名称（路径最后一段去后缀，否则用主机名），归入「未分组」 |

- 地址里的**查询参数完整保留**（含 token、`&`、`=`）。
- 只接受 `http://` / `https://` 地址；`rtmp://`、`rtsp://`、`udp://@239…` 等按无效条目跳过并计入跳过数（播放模块同样只支持 HTTP/HTTPS）。
- 不按后缀判断类型：`/live/stream?id=1`、`/tv.php?channel=2` 都当作有效频道地址。

### 3.2 TXT

| 写法 | 处理方式 |
| --- | --- |
| `频道名,地址` | 名称取第一个逗号前，地址取逗号后**全部内容**（地址自带逗号不受影响） |
| `频道名<TAB>地址` | 同上（制表符作为分隔符） |
| `分组名,#genre#` | 单独一行的分组声明，作用于其后的频道；`#genre#` 单独一行表示回到「未分组」 |
| 整行是一个地址 | 按地址推断名称 |
| `#` 开头的注释行、空行 | 忽略（不计跳过数） |
| 其他无法识别的行 | 计入跳过数 |

### 3.3 单个 HLS 流清单的识别

以下两种内容会被识别为**单个直播流**而不是频道列表，返回 `IMPORT_SINGLE_STREAM` 并提示改用「手动频道」：

- 含 `#EXT-X-STREAM-INF` / `#EXT-X-MEDIA` / `#EXT-X-I-FRAME-STREAM-INF`（主播放列表，同一路直播的多清晰度）；
- 含 `#EXT-X-TARGETDURATION` / `#EXT-X-MEDIA-SEQUENCE` / `#EXT-X-PLAYLIST-TYPE` / `#EXT-X-ENDLIST` / `#EXT-X-KEY` / `#EXT-X-MAP` / `#EXT-X-DISCONTINUITY`，且没有任何带频道名的 `#EXTINF`（分片清单）。

不会把每一个 `.ts` 分片导入成频道。带频道名的正常列表不会被误判（有对应测试）。

## 4. 编码处理

`TextDecoder` 的顺序：

1. BOM ≥ 内容：UTF-8 / UTF-16LE / UTF-16BE 的 BOM 优先，去掉 BOM 后按该编码解码；
2. 响应头 `Content-Type: …; charset=xxx` 声明（`gbk`/`gb2312` 归一为 GB18030）；
3. UTF-8 **严格**解码（非法字节即判失败，避免把 GBK 中文当 UTF-8 解出乱码）；
4. GB18030（GBK 超集）严格解码；
5. ISO-8859-1 兜底——保证任何字节都能得到文本，由"能否解析出频道"决定最终成败，不会因乱码直接抛异常。

换行：CRLF / CR / LF 统一处理（不依赖 `String.splitLines()`，Android 侧编译不可用，见 `TextLines`）。

## 5. 网络与本地读取的限制

| 项目 | 取值 | 说明 |
| --- | --- | --- |
| 连接超时 | 15 秒 | 构造参数可调（测试用） |
| 读取超时 | 20 秒 | 同上 |
| 单次内容上限 | 5 MB | HTTP 与本地文件一致；超限返回 `IMPORT_TOO_LARGE` |
| 重定向 | 最多 5 次 | 手动跟随；缺 Location、目标非法、出现循环、超次数都会明确报错 |
| 重试 | **不重试** | 单次 GET；不做任何绕过服务器验证的行为（无自定义 Cookie、无视鉴权、无换 UA 重试） |
| 请求头 | 固定 UA `Tunvix/0.1 (Android)`、`Accept`、`Accept-Encoding: identity` | 不使用 gzip，避免额外的解压路径 |
| 状态码 | 非 2xx 一律失败且**不读取响应体** | 因此 404/403 返回的 HTML 绝不会被当成列表 |
| 200 + 网页/JSON | 交给 `PlaylistSniffer` 判断，返回 `IMPORT_NOT_A_LIST` | 覆盖"登录页/错误页返回 200"的情况 |
| 本地文件 | 只接受 `content://` | 非 `content://`（含 `file://`、裸路径）返回 `IMPORT_URI_NOT_ALLOWED`；不使用全盘存储权限；URI 授权失效返回 `IMPORT_URI_PERMISSION_DENIED` |
| 取消 | 立刻断开连接 | 阻塞读会被 `disconnect()` 打断，不必等读超时；异常继续向上抛 |

单次导入最多 **20000** 个频道（`PlaylistParser.MAX_CHANNELS`），超出部分计入跳过数。

## 6. 错误码（`ImportErrorCodes`）

| code | 触发条件 |
| --- | --- |
| `IMPORT_INVALID_URL` | 地址不是 http/https 完整地址，或协议不受支持 |
| `IMPORT_NETWORK` | DNS 失败、连接被拒、TLS 失败等 |
| `IMPORT_TIMEOUT` | 连接/读取超时 |
| `IMPORT_HTTP_STATUS` | 非 2xx（message 里带状态码与中文解释） |
| `IMPORT_REDIRECT` | 重定向缺目标 / 目标非法 / 循环 / 次数过多 |
| `IMPORT_TOO_LARGE` | 超过 5 MB |
| `IMPORT_EMPTY_CONTENT` | 内容为空 |
| `IMPORT_NOT_A_LIST` | 网页、XML、JSON 等非列表内容，或完全无法识别的文本 |
| `IMPORT_SINGLE_STREAM` | 单个 HLS 流的播放/分片清单，提示改用手动频道 |
| `IMPORT_NO_VALID_CHANNEL` | 识别为列表但没有任何有效频道 |
| `IMPORT_URI_NOT_ALLOWED` | 本地地址不是 `content://` |
| `IMPORT_URI_PERMISSION_DENIED` | 持久化 URI 授权失效 |
| `IMPORT_FILE_UNAVAILABLE` | 文件不存在或读取失败 |
| `IMPORT_UNKNOWN` | 兜底 |

提示信息只包含**去掉查询参数**的地址（`UrlTools.sanitize`），不会把 token 写进日志或界面。

## 7. 解析结果语义

- 顺序：严格保持文件中的出现顺序（前端的分组顺序与切台顺序依赖它）。
- 去重：只有**名称 + 完整地址都相同**才算重复（同名不同地址、同地址不同名称都保留），重复条目计入 `skippedCount`。
- `skippedCount` = 无效条目 + 重复条目 + 超出上限条目，一次汇报给用户（对应 PRD"A02 有效与无效条目反馈明确"）。
- 解析过程**不访问任何频道地址**，不校验能否播放。
- 不生成 ID：`ChannelDraft` 只带解析结果，ID 与持久化由 catalog 模块负责。

## 8. 测试

```bash
# 在项目根目录
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :backend:importer:testDebugUnitTest --console=plain
```

结果：**8 个测试类 / 57 个用例，0 失败**（`backend/importer/build/test-results/testDebugUnitTest/`）。

| 测试类 | 覆盖 |
| --- | --- |
| `M3uParserTest`（14） | 属性顺序与引号、频道名/属性值里的逗号、未知标记与注释、EXTVLCOPT 优先级与前置、EXTGRP、查询参数完整、非媒体后缀、非 http 与缺地址跳过、裸地址命名、CRLF+BOM、主播放列表与分片清单识别、正常列表不误判 |
| `TxtParserTest`（7） | 两种写法、genre 切换分组、制表符、地址内逗号、注释/空行/垃圾行、GB18030 中文、空输入与大小写变体 |
| `PlaylistParserTest`（4） | 顺序保持、去重、上限截断、格式识别、单流提示、网页/JSON 嗅探 |
| `TextDecoderTest`（7） | UTF-8 无 BOM/BOM、UTF-16LE、GB18030 回退、声明编码优先、乱码不崩、CRLF/CR/LF |
| `UrlToolsTest`（3） | 协议白名单、名称推断、错误提示脱敏 |
| `LocalUriRulesTest`（1） | 只接受 `content://` |
| `HttpFetcherTest`（9） | 真实本地 HTTP 服务：重定向、404、重定向过多、超限、读超时、空体、声明字符集、非 http 不请求、**取消立刻结束** |
| `AndroidSourceImporterTest`（12） | 入口级：样本导入报告、TXT 分组、GBK 本地文件、网页拒绝、单流提示、零有效失败、未知格式、空内容、非法地址不请求、错误码透传、取消不被转换成失败 |

测试样本（固定、已脱敏，域名/token 均为占位值）：`backend/importer/src/test/resources/samples/`
`desensitized-extended.m3u`、`desensitized-plain.txt`、`hls-media-playlist.m3u8`、`webpage-error.html`。
仓库内不包含任何真实订阅地址。

`HttpFetcherTest` 用 `TestHttpServer`（`ServerSocket` 手写的极小服务）在本地起真实 HTTP 连接，
不需要公网、不需要真机，也不会访问外部地址。

## 9. 验证记录

| 命令 | 结果 |
| --- | --- |
| `:backend:importer:compileDebugKotlin` | 通过 |
| `:backend:importer:testDebugUnitTest` | 通过（57/57） |
| `:backend:importer:assembleDebug` | 通过 |
| `:app:compileDemoDebugKotlin` / `:app:compileLiveDebugKotlin` | 通过（回归确认新模块没影响 app 构建） |

## 10. 已知限制与未做的事

1. **未在真机验证 `ContentResolver` 路径**：单元测试覆盖了 URI 准入规则与错误映射，但真实文件选择器的 URI 读取需要设备/模拟器，留给最终集成联调（与 catalog、UI 一起验证）。
2. 不支持 `rtmp://`、`rtsp://`、`udp://`、组播等非 http(s) 协议（按无效条目跳过），与播放模块的能力范围一致。
3. 不支持 TXT 的 `[分组名]` 小节写法、以及仅用空格分隔的 `频道名 地址`（无法与含空格的频道名区分）；如有需要后续可按同样方式加解析分支。
4. `#EXTVLCOPT` 只取 User-Agent 与 Referer，其他 VLC 选项忽略；契约里的 `headers` 也只承载这两个键。
5. 解析不做流式处理（5 MB 上限内一次性读取）；如未来放宽上限需要改成按行流式解析。
6. 不处理 APTV 专有字段的语义，只按 PRD 要求保留名称/分组/台标/标识/请求头。
7. 未实现任何形式的服务器验证绕过、Cookie 注入或无限重试。

## 11. 给最终集成方的接口备注

- app 侧只需在 live 的 `AppServices` 工厂里 `AndroidSourceImporter(context)`，无需其他接线。
- 导入前建议先用 `report` 展示"频道数/分组数/跳过数"，用户确认后再 `commitImport`。
- 订阅地址重复检测、源命名、刷新策略属于 catalog 模块；导入模块只做解析。
- 若契约需要新增字段（例如后端专有标记），按并行文档约定走"提案 → 前端统一调整接口"，不在本模块私自扩接口。
