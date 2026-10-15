# backend/catalog 交付说明（对话 B：本地数据与截图缓存）

版本：v2 · 2026-10-07 · 负责人：本地数据对话（对话 B）

v2 变更：按 `docs/ABC-交付审查.md` 修复 B 的 6 项问题（保存原子性、过期刷新、写盘异常、旧截图回写、启动阻塞、截图串行），新增 8 个回归测试。详见第 10 节。

范围：本模块仅包含 `backend/catalog/` 与本文档。未修改 `app/`、根构建、`core/contract` 以及其他后端模块，也未改动 `settings.gradle.kts`（该文件在检测到 `backend/catalog/build.gradle.kts` 时自动包含本模块）。

## 1. 公开入口与依赖

```kotlin
// 生产装配（Android）
val catalog: CatalogRepository =
    com.tunvix.backend.catalog.LocalCatalogRepository(context, importer)
```

| 项目 | 内容 |
| --- | --- |
| 公开类 | `com.tunvix.backend.catalog.LocalCatalogRepository` |
| 公开构造 | `LocalCatalogRepository(context: Context, importer: SourceImporter)` |
| 实现接口 | `com.tunvix.contract.CatalogRepository`（以 `Contracts.kt` 实际声明为准，逐条实现，无新增契约成员） |
| 编译依赖 | `project(":core:contract")`；`compileSdk = 37`、`minSdk = 29`；复用根工程插件版本 |
| 测试依赖 | `junit:junit:4.13.2`、`kotlinx-coroutines-test:1.10.2` |
| 额外公开成员 | `fun close()`（取消后台截图任务并结束内部作用域）；`val ready: StateFlow<Boolean>` 与 `suspend fun awaitReady()`（初始化完成信号）。三者都不在契约内 |
| 对导入模块的依赖 | 仅 `SourceImporter` 接口，构造函数注入；不引用 `backend/importer` 的任何实现类型 |

### 启动时不阻塞主线程

`LocalCatalogRepository(context, importer)` **不在调用线程读盘**：构造后立即返回，`catalog.tsv` 由后台协程读取，读完后才发布到 `snapshot`。

- 读到真实数据之前，`snapshot` 是「全新安装」的空快照（只有空的「自建频道」），**不会**对外表现为已加载。
- `ready`（`StateFlow<Boolean>`）在读盘结束（成功或失败）后变为 `true`；`awaitReady()` 是等价的挂起版本。前端可用它在首页显示加载态。
- 初始化期间的任何写操作（导入、收藏、改设置……）都会先等 `ready`，因此**不可能用空状态覆写尚未读出的磁盘数据**。
- 契约 `CatalogRepository` 未改动：消费方仍然只观察 `CatalogSnapshot`，`ready` 只是本类多出来的一个信号。

`Outcome.Failure.code` 是本模块内部约定的小写标识（`duplicate` / `missing` / `custom` / `custom_source` / `not_custom` / `uri_permission` / `empty` / `name` / `url` / `location` / `storage` / `stale`），其中 `duplicate` 与现有演示适配器保持一致，前端可沿用同一分支；`message` 一律是可直接展示的中文说明。导入模块使用 `IMPORT_*` 前缀，两者互不影响，`refreshSource` 会把导入模块的错误信息与错误码原样透出。

新增两码的语义：

| code | 触发 | 前端建议 |
| --- | --- | --- |
| `storage` | 磁盘写入失败（IO 异常 / 无法替换文件）。**内存快照与磁盘文件都停留在旧状态**，不会出现「提示成功但没存上」 | 按普通错误展示即可，`message` 已说明「已保留原有数据」 |
| `stale` | 刷新期间该源被改名/改地址/重新导入，或又发起了一次更新的刷新，本次结果已被丢弃 | 可静默忽略（属于用户自己触发的变更），不当成失败弹窗 |

前端最后集成时的装配（导入模块已由对话 A 交付并列在同一工程，播放模块由对话 C 交付）：

```kotlin
val importer = com.tunvix.backend.importer.AndroidSourceImporter(context)
val catalog  = com.tunvix.backend.catalog.LocalCatalogRepository(context, importer)
```

内部实现全部为 `internal`（`CatalogStore` / `FileCatalogStore` / `CatalogWriteException` / `CatalogCodec` / `ChannelIdentity` / `ThumbnailStore` / `ThumbnailSource` / `UriAccess`），生产实现与测试使用同一份存储、匹配、缓存代码，只在平台边界（截图抓取、URI 权限）注入假实现。唯一为测试留出的注入口是 `FileCatalogStore` 的替换函数 `mover`（默认就是 `File.renameTo`），用来复现替换失败。

`Outcome.Failure.code` 是本模块内部约定的小写标识（`duplicate` / `missing` / `custom` / `custom_source` / `not_custom` / `uri_permission` / `empty` / `name` / `url` / `location`），其中 `duplicate` 与现有演示适配器保持一致，前端可沿用同一分支；`message` 一律是可直接展示的中文说明。导入模块使用 `IMPORT_*` 前缀，两者互不影响，`refreshSource` 会把导入模块的错误信息与错误码原样透出。

## 2. 契约实现对照

| 接口方法 | 行为要点 |
| --- | --- |
| `snapshot` | `StateFlow<CatalogSnapshot>`，只发布不可变快照；ID 是唯一关联依据 |
| `selectSource(id)` | 源不存在返回 `Failure("直播源不存在", "missing")` |
| `commitImport(name, request, report, replaceSourceId)` | 首导创建源并选中；`replaceSourceId` 为重新导入（替换而非新增）；重复地址返回 `code = "duplicate"` |
| `refreshSource(id)` | 先联网解析，成功后一次性原子替换；任何失败都完整保留旧数据；期间源被改动或又有更新的一次刷新时丢弃本次结果（`code = "stale"`） |
| `updateSource(id, name, location)` | 名称/地址校验；重复地址拒绝；自建频道源只允许改名，地址恒为 `null` |
| `deleteSource(id)` | 自建频道入口拒绝删除（`code = "custom_source"`）；只清理本源的频道、截图、收藏、最近 |
| `saveCustomChannel(channelId, draft)` | 新建/编辑自建频道；编辑保持原位置；校验名称与 http/https 地址 |
| `deleteCustomChannel(id)` | 只能删除自建频道（`code = "not_custom"`） |
| `setFavorite(id, favorite)` | 收藏写在频道行上，频道消失即自动清理，不会出现悬空 ID |
| `recordWatched(id)` | 按时间置顶、去重，超出上限自动淘汰最旧记录 |
| `removeRecent(id)` / `clearRecents()` | 只清观看记录，不影响收藏与源 |
| `updateSettings(settings)` | 移动网络提醒、震动开关持久化 |
| `clearThumbnailCache()` | 删除磁盘截图并清空快照中的 `thumbnailUri`；源、收藏、最近、设置一律不动 |
| `requestThumbnails(channelIds, allowNetwork)` | 只处理传入的可见 ID；`allowNetwork = false` 不产生任何新请求；3 并发、5 秒超时、可取消、失败静默回退 |

## 3. 行为细则

### 3.1 全新安装

- 只有一条空的「自建频道」源（`id = "custom"`，`kind = CUSTOM`），无任何示例频道、无内置订阅。
- 不自动刷新订阅，启动只读取本地列表。

### 3.2 导入与重新导入

- 先由前端完成解析（`importer.inspect`），把 `ImportReport` 交给 `commitImport` 落库。
- 同一地址（订阅 URL / 文件 URI）再次导入且未指定 `replaceSourceId` → `Failure("该订阅已存在，请刷新已有直播源", "duplicate")`，不创建第二个源。
- 指定 `replaceSourceId` 时替换该源的频道集合，源的 `id`、选中状态与其他源顺序不变。
- 同一份报告中 `(名称, URL)` 完全相同的重复条目只保留第一条。

### 3.3 刷新（原子替换）

1. 源不存在 / 自建频道 / 无地址 → 立即失败，不联网。
2. 本地文件源先检查持久授权，失效返回 `code = "uri_permission"`，不会去读文件。
3. 联网解析在写锁之外执行，慢服务器不会阻塞其他操作。
4. 解析失败 → 原样返回失败原因；解析成功但零频道 → `Failure("刷新没有返回有效频道，已保留原有列表", "empty")`。
5. 成功时一次性替换该源的频道：其他源、其他源的频道位置、收藏、最近、设置都不受影响。

**过期刷新不会被写回（源码级保护）**：每个源维护一个「代次」（`generations`）。刷新**开始**时占用下一代次，提交前核对代次是否仍是自己占用的那一代；不一致就返回 `Failure("刷新期间直播源已被修改，本次结果已丢弃", "stale")` 并**完全不写入**。以下动作都会推进代次：

- `updateSource` 改了该源的名称/地址；
- `commitImport(..., replaceSourceId = 该源)` 重新导入覆盖它；
- 又发起了一次该源的刷新（更晚发起者胜出）。

这样同时覆盖两种竞态：① 联网等待期间用户改了地址或重新导入，慢刷新的结果不会把用户的新选择顶掉；② 两次刷新乱序完成时，**晚发起的那次结果保留**，先发起的旧结果被丢弃。

**频道身份匹配（ID 跨刷新稳定）**，按优先级逐轮匹配，只在本源内进行：

1. `externalId`（tvg-id）——最强依据；
2. 归一化「名称 + 分组」；
3. 去掉易变查询参数后的 URL（`token`、`sign`、`expire`、`e`、`ts`、`auth`、`txsecret` 等，见 `ChannelIdentity.VOLATILE_QUERY_KEYS`），参数顺序无关，主机名大小写无关，路径不同视为不同频道。

每个旧频道最多被认领一次。匹配上的频道保留原 `id`、收藏、观看时间；URL 或请求头变化时旧截图作废并删除，重新抓取。消失频道的收藏与最近记录一并清理（对应 PRD 中「保留可匹配的收藏和历史」）。同名频道在不同源绝不合并。

### 3.4 删除隔离

- 删除源：只移除该源的频道及其截图；若删的是当前源，切换到「有频道的第一个源」，其次自建频道，再次任意剩余源，都没有则为 `null`。
- 自建频道源本身永远保留（`deleteSource` 拒绝）；其频道可逐条删除。
- 订阅频道的 `saveCustomChannel` / `deleteCustomChannel` 一律拒绝，只能通过刷新或重新导入维护。

### 3.5 收藏与最近

- 收藏就是频道行上的标记，因此删除源/频道、刷新丢失频道时自动同步清理，快照中不存在指向已删频道的 ID。
- 最近观看按 `watchedAt` 倒序、按 `id` 去重，上限 100 条；超限时把最旧记录的 `watchedAt` 清空（不删除频道）。

### 3.6 本地文件授权

- 数据层只保存前端选中的 URI（`LiveSource.location`）。
- 持久化访问权限由前端获取：
  `context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`（配合 `OpenDocument` 返回的 URI）。
- 刷新前用 `UriAccess` 校验：既有持久授权，也会实测 `openInputStream`；失效时返回可展示错误「本地文件授权已失效，请重新导入该文件」，不写入任何数据。

## 4. 截图保存与调度

- 生产抓帧使用独立静音 ExoPlayer，从直播默认位置连续接收首帧，通过 SurfaceTexture 输出及 PixelCopy 生成至多 320×180 JPEG；不 seek 到 0，不申请音频焦点，关闭音频和字幕轨道。支持 HLS 及频道请求头；无后缀格式解析失败时可回退 HLS。
- 首页、收藏、最近按可见频道优先并预取下一屏，搜索遵循当前结果；移动网络与 Wi-Fi 均支持。手动刷新仍覆盖当前分组全部频道。
- 自动补图与手动刷新共用 3 个解码名额；自动队列最多 3 个工作协程，手动批次最多 3 个工作协程，不为整组创建逐频道协程。
- 每频道从拿到名额开始计时，整个格式识别与回退共享 5 秒。超时释放播放器和 Surface 并跳过，其他工作协程继续，失败不会中止整组。
- 自动补图只获取缺失截图，失败冷却 10 分钟；手动刷新绕过冷却，结束显示成功与失败数，失败保留旧图。
- 进入播放、后台或切换目标集合时取消不需要的任务；手动刷新先取消并等待自动任务释放资源。同一频道不会由自动和手动同时抓取。
- 实际播放渲染首帧后可通过 PixelCopy 补存缺失截图，不另开直播连接；保存前校验频道和媒体身份，保留已有图。
- 截图保持最大 320×180、JPEG 质量 65；新图与目录提交成功后才删除旧图，媒体 URL 或请求头改变时失效。失败的目录保存、编辑、删除或清理不会删除旧截图。
- 不再按 24 MiB 自动淘汰。每频道只保留当前引用截图，删除源/频道和显式清理时删除，启动时清理无引用文件。
- 应用级 12 MiB 内存 LRU 按 URI 缓存解码图、合并并行读取。卡片回到屏幕优先同步取缓存，刷新加载过程中保留旧图；内存淘汰不删磁盘文件、不重新抓视频。

完整调整及验收见 [缩略图并发与稳定显示](缩略图并发与稳定显示.md)。

## 5. 本地存储与迁移策略

| 项目 | 位置 / 策略 |
| --- | --- |
| 目录数据 | `filesDir/catalog/catalog.tsv`（单文件，整目录原子替换） |
| 截图缓存 | `filesDir/thumbnails/<channelId>-<fingerprint>-<captureId>.jpg`（持久保存；启动迁移旧 cacheDir 图片，保留目录引用，缺失的旧图无法恢复） |
| 写入方式 | 先写 `catalog.tsv.tmp`，再用一次 `rename` 安装到目标：进程被杀不会留下半份数据 |
| 格式 | 行式文本，首行 `tunvix-catalog <版本>`；每个字段 URL-encode 转义，制表符、换行、逗号、中文、`&`/`=` 都能安全往返；无法解析的行跳过而不是整份作废 |
| 版本 | 当前 `CATALOG_FORMAT_VERSION = 1`；`CatalogCodec.migrate(from, body)` 是按版本逐级转换的迁移阶梯，升级时在此追加 `2 -> 3` 之类的步骤 |
| 未知版本 | 读到比本版本更新的文件时**不冒险解析**：重命名为 `catalog.tsv.corrupt` 并回到全新安装，避免用旧代码误读新结构 |
| 降级/回滚 | 旧版本代码不会再写回被隔离的文件；截图缓存可随时整体删除而不影响任何个人数据 |

不使用 SQLite / Room 的理由：模块本身始终在内存中持有完整快照，写入是「整份替换」，列表规模为个人使用的数千频道量级，单次原子写入耗时在同量级下可忽略；这样生产实现与 JVM 单元测试走同一条代码路径，迁移逻辑也只是版本号加转换函数，不需要额外的数据库迁移框架。若后续频道规模或写入频率显著增长，可在 `CatalogStore`（只有 `read()` / `write()` 两个方法）后面换成 SQLite 实现，上层逻辑与测试无需改动。

### 5.1 写入失败与数据安全（失败不丢数据）

- **旧文件永远先于新文件存活**：只有当 `rename` 无法替换已存在的目标（个别文件系统）时，才退化为「旧文件移为 `.bak` → 新文件就位 → 删除 `.bak`」。任何一步失败都会把旧文件还原回原位并抛 `CatalogWriteException`。**代码里不存在「先删除旧文件再改名」的分支**——那正是 v1 在替换失败时会丢数据的根因。
- **失败一定被报出来**：`mutate` 捕获写入异常（`CancellationException` 除外，协程取消照常向上传播），返回 `Outcome.Failure("保存失败，已保留原有数据", "storage")` 并**不发布新快照**。不会出现「界面显示成功、磁盘其实没写」，也不会因未处理异常崩溃。
- **不残留垃圾**：失败路径下 `.tmp` / `.bak` 都会被清掉；`ThumbnailStore.write` 写盘失败会删除半份文件并让调用方回退，缓存索引与磁盘始终一致。
- **启动不阻塞主线程**：生产构造异步读盘（见第 1 节），写操作在 `ready` 之前一律等待，杜绝用空状态覆写磁盘。

## 6. 上限与常量

| 常量 | 值 | 位置 |
| --- | --- | --- |
| 最近观看上限 | 100 | `RECENT_LIMIT` |
| 截图缓存上限 | 64 MB（超出按最后修改时间淘汰） | `ThumbnailLimits.MAX_CACHE_BYTES` |
| 截图并发 | 1（逐个获取，PRD 4.4） | `ThumbnailLimits.MAX_CONCURRENT` |
| 截图排队上限 | 48 | `ThumbnailLimits.QUEUE_LIMIT` |
| 单次抓取超时 | 10 s | `ThumbnailLimits.EXTRACT_TIMEOUT_MS` |
| 失败重试冷却 | 10 min（冷却表 512 条） | `ThumbnailLimits.RETRY_COOLDOWN_MS` / `MAX_COOLDOWN_ENTRIES` |
| 图片下载 | 连接 6 s / 读取 8 s / ≤2 MB / 宽 480 JPEG q85 | `AndroidThumbnailSource` |

## 7. 验证

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :backend:catalog:testDebugUnitTest :backend:catalog:lintDebug \
  :backend:catalog:assembleDebug :app:assembleLiveDebug --rerun-tasks --console=plain
```

结果（2026-10-07，Gradle 9.3.1 / JVM 25，全部使用 `--rerun-tasks`）：

- `:backend:catalog:testDebugUnitTest` — **39 个测试全部通过**（v1 为 31，本次新增 8 个回归测试），无编译警告。
- `:backend:catalog:lintDebug` — 通过；仅剩 1 条与本模块无关的依赖版本提示（`kotlinx-coroutines-test` 有 1.11.0 可用），0 error。
- `:backend:catalog:assembleDebug` — 通过。
- `:app:assembleDemoDebug` / `:app:assembleLiveDebug` — 通过（确认新增模块未影响原有工程）。

**回归测试的变异验证**（证明新测试确实能捕获原缺陷，而非事后补的摆设）：临时移除对应防护后重跑，测试如期失败，随后还原。

| 移除的防护 | 失败的测试 |
| --- | --- |
| `refreshSource` 的代次核对 | `aRefreshThatLosesItsRaceWithAnAddressChangeIsDiscarded`、`anOlderRefreshNeverOverwritesTheResultOfANewerOne` |
| 截图提交时的指纹核对 | `aStillThatFinishesAfterItsChannelWasRefreshedIsNotAttachedToTheNewStream` |
| `mutate` 的写盘异常捕获 | `aFailedSaveIsReportedAndLeavesBothTheSnapshotAndTheFileUntouched`（异常直接冒到调用方，即审查指出的崩溃路径） |

测试覆盖（`backend/catalog/src/test/kotlin/...`）：

| 测试类 | 数量 | 覆盖内容 |
| --- | --- | --- |
| `LocalCatalogRepositoryTest` | 21 | v1 的 15 项（全新安装、导入顺序、重复订阅拒绝与重导入替换、刷新失败/空结果保留旧数据、刷新成功保 ID 与清理、跨源同名独立、删除隔离与选中回退、自建频道 CRUD、订阅频道拒绝走自建接口、最近去重与上限、截图网络许可与失败回退、本地文件授权、源改名与重复地址校验）＋ 本次新增：**逐个获取且离开可视即取消**、**改地址后过期刷新被丢弃**、**两次刷新乱序完成时旧结果不覆盖新结果**、**写盘失败返回 `storage` 且快照与磁盘都停在旧状态**、**频道刷新后完成的旧截图不挂到新地址**、**异步初始化 `ready` 语义**、**就绪前的写操作不会覆写磁盘** |
| `CatalogPersistenceTest` | 11 | v1 的 9 项（重启全量一致、同频道只留一份截图、token 变化后 ID 保留且截图重建、清缓存不动个人记录、编解码特殊字符往返、更高版本/非法文件不误读、坏行跳过、损坏文件隔离、写入原子不留临时文件）＋ 本次新增：**连续两次替换失败仍保留旧目录且可重启读回、无 `.tmp`/`.bak` 残留**、**首次 rename 失败时回退为备份交换并成功** |
| `ChannelIdentityTest` | 7 | tvg-id 优先；token 轮换走 URL 回退；易变参数与主机大小写归一；路径不同不匹配；每个旧频道只被认领一次；新频道不匹配；指纹随 URL/请求头变化 |

## 8. 已知限制

1. **Android 专有实现只做了编译验证**：本机没有连接真机或模拟器（`adb devices` 为空），`AndroidThumbnailSource`（`MediaMetadataRetriever`、图片下载）与 `AndroidUriAccess`（持久 URI 权限）未被真机执行过；已由接口抽象隔离，逻辑层行为由 JVM 测试覆盖。首次真机联调时建议优先验证这两处。
2. **直播 HLS 不生成截图**：无法在不与播放器争抢资源的前提下抓帧，按 PRD 回退台标/文字占位。若后续需要真实直播截图，建议在 `ThumbnailSource` 后接 Media3 帧提取，并保持「非播放状态才申请」的前提。
3. **协程取消不中断阻塞调用**：平台抓帧/下载是阻塞调用，取消只能保证「不再排新任务 + 丢弃结果」，不能硬中断已经在执行的那一次解码（详见第 4 节）。当前的缓解是串行 + 直播不抓帧 + HTTP 硬超时。若真机实测出现播放卡顿，下一步应把 `ThumbnailSource` 换成可中止的实现（例如 Media3 帧提取 + 可关闭的连接），而不是继续调并发参数。
4. **整份文件写入**：频道规模达到数万级时，每次收藏/观看记录都要重写整份文件；届时应换成 `CatalogStore` 的 SQLite 实现（接口已收窄到 `read()`/`write()`）。
5. **异步初始化改变首帧语义**：生产构造下 `snapshot` 在 `ready` 为 `false` 时是空快照。前端**必须**用 `ready` 做加载态判断，不能把「空列表」直接当成「用户没有任何源」展示（否则首次启动会闪一下空态）。
6. **未实现**：源排序、分组重命名/删除、备份恢复、APK 升级迁移（PRD 第 8 节中尚未锁定的细节）；跨源同名的去重展示属于前端展示策略。

## 9. 需要前端统一决定的提案

本模块没有修改公共契约。若集成时需要以下能力，请由前端统一调整 `core/contract` 后再通知本模块：

- 源排序 / 源置顶（当前顺序为「自建频道 + 导入顺序」）；
- 截图生成完成的事件通知（当前通过 `snapshot` 中 `thumbnailUri` 的变化体现）；
- 删除源时返回被移除的频道数量，用于更精确的确认文案（当前由前端在删除前自行统计）。

## 10. v2 修复对照（回应 `docs/ABC-交付审查.md`）

| 审查项 | 原缺陷 | 修复 | 回归测试 |
| --- | --- | --- | --- |
| B-1 P1 保存失败丢旧数据 | 首次 `rename` 失败即 `file.delete()`，二次 `rename` 结果未检查 | `FileCatalogStore.replace` 改为「先安装新文件、再丢弃旧文件」，失败抛 `CatalogWriteException`，不再存在删旧文件的分支 | `twoFailedReplacementsKeepTheLastGoodCatalogAndReportTheFailure`、`aFailedDirectRenameFallsBackToASwapThatOnlyDropsTheOldFileOnceTheNewOneIsInPlace` |
| B-2 P1 过期刷新覆盖新订阅 | 提交只核 ID，改地址/重导入后仍写回旧结果 | 引入 per-source 代次，改动/重导入/新刷新都会推进；代次不符返回 `stale` 且不写入 | `aRefreshThatLosesItsRaceWithAnAddressChangeIsDiscarded`、`anOlderRefreshNeverOverwritesTheResultOfANewerOne` |
| B-3 P2 写盘异常直冒 | `store.write` 异常直接传播，调用方可能崩 | `mutate` 捕获后返回 `Outcome.Failure(..., "storage")`，不发布新快照，`CancellationException` 照常上抛 | `aFailedSaveIsReportedAndLeavesBothTheSnapshotAndTheFileUntouched` |
| B-4 P2 旧截图回写新频道 | 提交只核频道 ID | 提交前核对 `ChannelIdentity.fingerprint(url, headers)`，不符则删除刚落盘的截图并丢弃结果 | `aStillThatFinishesAfterItsChannelWasRefreshedIsNotAttachedToTheNewStream` |
| B-5 P2 构造同步文件 IO | 构造即在调用线程 `store.read()` | 生产构造改为异步读盘 + `ready` / `awaitReady()`；写操作在就绪前等待，杜绝覆写磁盘 | `theProductionPathReadsTheCatalogOffTheCallingThread`、`aMutationArrivingBeforeTheBackgroundReadFinishesWaitsInsteadOfOverwritingTheCatalog` |
| B-6 P2 截图并发与 PRD 不符 | `MAX_CONCURRENT = 2` | 改为 1（逐个获取）；并在文档如实说明阻塞调用下取消的真实语义 | `stillsAreFetchedOneAtATimeAndScrollingAwayCancelsTheInFlightOne` |

前端集成时新增的两处配合（补充第 1、4 节）：

1. 首页用 `ready` 判断加载态；`LocalCatalogRepository` 装配后即可用，`awaitReady()` 可选。
2. 离开列表/进入播放时用**新的可见 ID 列表**（离开列表传空集合）调用 `requestThumbnails`，不要只停止发新请求。

## 11. 前端负责人的直接修复（2026-10-07）

此节覆盖此前 v2 尚未完全修复的边界问题，详见 `docs/BC-修复验收.md`。

- read/write 入口恢复有效 `.bak`；交换中断或回滚失败后仍可在重启恢复最后有效目录。临时文件写入后同步文件描述符，再安装。
- 现有目录读取错误向上传播，不再当作首次安装。异步初始化通过 `initializationError: StateFlow<String?>` 暴露错误，初始化失败时所有保存/刷新/截图申请返回 storage，不允许空快照覆写旧文件；重启可重试读取。
- `ready` 表示初始化流程结束，不单独代表成功；正式装配需要同时检查 `initializationError`。
- 源快照与刷新代次在同一写锁内取得，覆盖 URI 权限检查期间源被修改的窗口。
- ThumbnailStore 构造不做 IO，缓存初始化与目录加载一起在后台执行。
- 自建频道按 URL 与 headers 指纹失效截图。
- 新增 7 项回归测试，均在修复前观察到失败，修复后通过。catalog 总计 46 项测试。

## 0.2.1 外观与浏览偏好

`UserSettings.themeMode` 为 SYSTEM / LIGHT / DARK，旧目录默认 SYSTEM；settings 行末追加字段。按直播源保存首页分组的 `browse-group` 行使用现有转义规则；旧版本忽略新增行。源删除同步清理偏好，分组失效时前端回退到首个有效分组。格式版本继续为 1，已有频道、收藏、历史和截图数据保留。
