# ABC 与 UI 二次全面复审

日期：2026-10-07。本文件记录修复前发现；用户随后授权当前对话直接修复，最新状态见 `docs/BC-修复验收.md`。

## 验证结果

- 带 `--rerun-tasks` 全量运行成功：A 57 项、B 39 项、C 24 项；UI demo/live 各 12 项。合计 144 次测试执行，零失败。
- ABC lint、app live lint、live Debug APK 构建均成功。C 的 8 个 Media3 opt-in 错误已消除。
- 新增两个异常场景探针均失败，证明仍有缺陷；不是把既有测试失败误当成修复退化。
- 正式版 UI 仍未连接 ABC，构建不能替代完整导入→持久化→播放链路验收。真机与 Android 10 验收仍未完成。

## 仍需 B 修复

### P1：备份交换中断后不能恢复（已测试复现）

`backend/catalog/src/main/kotlin/com/tunvix/backend/catalog/CatalogStore.kt:87`、120、125。

旧文件移到 `.bak` 后进程被杀，或安装新文件与备份回滚都失败，正式文件缺失。read 只检查正式文件，直接返回 freshInstall，从不恢复有效 `.bak`。探针保存 hapticsEnabled=false 的旧目录，将正式文件移至 bak（模拟交换中断），重启读回得到默认 true，断言失败。

需恢复有效备份、核对回滚成功与否，避免后续写入掩盖旧数据；补测交换各步骤进程中断和回滚失败，不仅测试正常返回的替换失败。

### P1：读取旧数据失败后可能覆写原文件

`CatalogStore.kt:89`、`LocalCatalogRepository.kt:136`。

读取异常被转为全新安装；初始化异常也被标记 ready。随后修改设置/导入会写入空状态，从而覆盖尚未成功读取的旧目录。需区分“首次安装无文件”和“现有文件读取失败”，失败状态禁止保存覆盖，提供可恢复的错误。此项依据代码确认，尚未新增执行故障注入测试。

### P1：源读取与刷新代次领取不是原子操作

`LocalCatalogRepository.kt:208`、228、241、250。

刷新先读取旧地址 A，之后才领取代次。在两者间 updateSource 可将地址改为 B 并推进代次；旧刷新随后领取更新的代次，通过提交核对，仍把 A 写回。应在同一个写锁内获取源快照并领取刷新代次。现有测试只覆盖领取代次之后的修改。

### P2：异步启动仍同步扫描截图目录

`LocalCatalogRepository.kt:72` 创建 ThumbnailStore；`Thumbnails.kt:53` 起构造执行 mkdirs、listFiles、isFile、length。主线程创建仓库时仍同步 IO。缓存索引初始化也应移至后台，保持 ready 与写入等待语义。

### P2：自建频道修改 headers 仍保留旧截图

`LocalCatalogRepository.kt:328`–332 只比较 URL。相同 URL 改 User-Agent/Referer，仍保留旧 thumbnailName；应按 URL+headers 指纹失效，与订阅刷新保持一致。

## 仍需 C 修复

### P2：永久丢失焦点后迟到 READY 自动播放（已测试复现）

`backend/playback/src/main/kotlin/com/tunvix/backend/playback/Media3PlaybackController.kt:266`、385。

连接中收到 LOST，代码把 pausedByTransientFocus 设为 false，仅执行 engine.pause。随后 READY 到达，userPaused/background/transient 三个标记均 false，执行 engine.play。探针顺序为 play→LOST→READY，断言 playCount 不增加，实际增加而失败。

需要持久记录永久焦点暂停，只有用户主动恢复才清除；覆盖连接、缓冲、重连期间 LOST 后 READY、GAINED 等事件。

### P2：退避重连不遵守永久焦点暂停

`Media3PlaybackController.kt:305`–311、382–388。

错误建立退避任务后发生 LOST，焦点处理未取消退避；任务只检查后台状态，重新申请焦点并 prepare，获准即自动播放。应取消或阻止自动重连出声，保留需用户恢复的状态。此前“后台取消退避”修复有效，但不覆盖焦点暂停。

## 已修复并保留的结论

- 普通写盘异常返回 storage 且不发布新快照。
- 领取刷新代次之后的改地址、重导入和乱序刷新能被丢弃。
- 在途截图提交核对 URL/headers 指纹。
- 截图并发改为 1。
- 后台取消退避，后台错误不触发重连。
- play/retry/resume 焦点申请拒绝路径不自动出声。
- Media3 opt-in 正确声明，lint 无错误。

## 集成与设备验证缺口

- 前端仍需要正式装配 ABC、加载态 ready、关闭 catalog 的生命周期和离开列表时空截图集合取消。
- 实际 Media3 快速切台回调隔离、Surface 重绑、侧键音量、耳机断开、文件授权、后台停流需设备测试；假引擎测试不能替代这些结论。未把尚未证明的真实引擎旧事件问题列为确定缺陷。
- 阻塞截图取消不能立即终止平台调用，也不能在调用退出前释放解码槽；文档不得承诺“立即释放”。
- HLS 截图能力与 HTTP 明文配置仍是已知待集成项，不能宣称全部实现。

## 可复制的返修指令

**B：** 请按 `docs/BC-二次全面复审.md` 的 B 部分修复。只改 backend/catalog 和自己的交付文档。优先解决备份恢复、读取失败禁止覆写、源快照与代次锁内获取；补足故障注入与竞态测试，再解决缓存初始化 IO 和自建 headers 截图失效。单测、lint 全部通过后交付。

**C：** 请按同文档 C 部分修复。只改 backend/playback 和自己的交付文档。永久焦点丢失需要持久暂停原因，迟到 READY 和退避任务都不能自动恢复播放；增加 play→LOST→READY 与错误退避→LOST 的回归测试。单测、lint 全部通过后交付。

## 临时复现证据

临时测试位于 `/private/tmp/tunvix-reaudit/`，通过外部 Gradle init script 加入测试 Kotlin 源目录，未改项目 Gradle 或后端测试文件。

失败报告副本：`artifacts/reaudit/TEST-com.tunvix.backend.catalog.CatalogAuditProbeTest.xml` 与 `artifacts/reaudit/TEST-com.tunvix.backend.playback.PlaybackAuditProbeTest.xml`。临时探针结束后按正常配置重新运行既有模块测试。
