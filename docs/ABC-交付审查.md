# ABC 交付审查

日期：2026-10-07。范围：三个后端模块、公共契约与当前 UI 装配。只读审查后端，未修改 ABC 实现。

## 结论与实际验证

三个模块代码、公开入口和交付文档均已到位；尚不满足整体验收。必须先修复 B/C 的下列问题，再由前端统一接入和真机联调。

| 模块 | 重新执行单元测试 | lint | 判断 |
| --- | --- | --- | --- |
| A importer | 57 通过，0 失败 | 通过 | 本次未发现阻断问题，Android 文件读取路径仍待真机验证 |
| B catalog | 31 通过，0 失败 | 通过 | 数据保存、刷新与截图竞态需修复 |
| C playback | 18 通过，0 失败 | 失败：8 错误、2 警告 | 后台重连、焦点拒绝和 Media3 opt-in 需修复 |

测试使用 `--rerun-tasks`，不是仅依赖缓存。当前 `:app:assembleLiveDebug` 成功，但 app 仍未依赖 ABC、live 工厂仍使用 MemoryCatalog / PreviewPlayback，故构建成功不能证明真实导入和播放链路已经集成。没有完成真机或 Android 10 验收。

## B 必须修复

1. **P1 保存失败可能删除旧目录数据**：`backend/catalog/src/main/kotlin/com/tunvix/backend/catalog/CatalogStore.kt:80`。首次 rename 失败后删除旧文件，再次 rename 结果未检查。可能丢失旧数据并报告成功。应采用能保留旧数据的原子替换，失败明确返回；补测两次替换失败、旧文件保留和重启恢复。
2. **P1 过期刷新覆盖新订阅**：`LocalCatalogRepository.kt:166`。刷新地址 A 等待网络时用户改为 B 或重导入 B；提交仅检查 ID，175 行写回 A。需核对源版本或请求地址并丢弃过期结果，补测修改地址和刷新乱序完成。
3. **P2 写盘异常未转 Outcome.Failure**：`LocalCatalogRepository.kt:402`。store.write 的异常直接传播，UI 调用者按契约处理 Failure，可能因未处理异常崩溃。需返回可展示错误，保持旧快照；不能吞协程取消。补测磁盘写失败。
4. **P2 旧截图回写更新后的频道**：`LocalCatalogRepository.kt:355`。提交只检查频道 ID，不核对 URL/headers 指纹；token 刷新后可关联旧截图。需核对指纹，丢弃旧任务结果；补测更新与截图完成交错。
5. **初始化同步文件 IO**：构造时 `LocalCatalogRepository.kt:75` 读取文件并构建缓存快照。主线程装配会阻塞启动，需给出异步初始化/后台创建的可用方案，保留当前契约兼容。
6. **PRD 截图逐个获取与当前并发 2 不符**：`Thumbnails.kt:10`。按既定需求改为串行。平台抓帧/下载为阻塞调用，协程 withTimeout 不等于可中断硬超时；交付文档应如实说明并验证取消不继续抢占主播放资源。

给 B 对话的任务：只修改 backend/catalog 与自己的交付文档，按上述问题补失败场景测试后修复，重新跑单测、lint；不得编辑 app、契约或其他模块。重点证明任何保存失败不丢旧数据，过期刷新/截图不能覆盖新状态。

## C 必须修复

1. **P1 后台自动重连继续拉流**：`backend/playback/src/main/kotlin/com/tunvix/backend/playback/Media3PlaybackController.kt:179`。断流产生 retryJob 后进入后台，后台只 stopStream，不取消任务或使准备代次失效；299–303 行延迟任务仍 startPrepare(autoplay=true)。补测退避期间后台、后台收到旧错误回调、返回前台一次恢复，并保留用户暂停意图。
2. **P2 焦点申请被拒仍播放**：`Media3PlaybackController.kt:114`、126、152。focus.request 返回值未使用。拒绝时不得继续自动出声；补测 play/retry/resume 申请失败。
3. **lint 阻断**：`Media3PlayerEngine.kt:36` 起共 8 处 UnsafeOptInUsageError。针对 Media3 不稳定 API 正确声明 opt-in，不能关闭 lint 或创建 baseline 掩盖问题；重新跑 lintDebug。

给 C 对话的任务：只修改 backend/playback 与自己的交付文档，按以上场景补测并修复，单测和 lint 均通过后重新交付。保留单一播放器和 Surface 重绑定行为。

## 前端集成责任（当前对话）

- app 的 live 依赖 ABC，工厂组装 AndroidSourceImporter、LocalCatalogRepository 和 `Media3PlaybackController.create(context)`（C 实际采用 create 工厂，与原任务构造入口有所不同，但可直接适配），backendReady=true。
- 列表离开/进入播放必须请求空截图集合以取消在途任务，不能只停止新请求；当前 HomeScreens.kt:64–65 仅请求非空集合，未实现退出清理。
- 对 LocalCatalogRepository.close 提供生命周期清理；接口目前没有统一 release，装配时处理具体实现即可。
- HLS 真实截图当前未实现；B 先取台标再取渐进式媒体帧，不能把台标说成直播截图。无可用画面时使用占位。
- 网络安全仍使用默认配置：模块代码支持 HTTP 不等于 APK 允许明文 HTTP。此前全局明文开关被自动审批拒绝，未执行；集成阶段需明确可接受的具体配置。
- 真机验证导入、重启持久化、播放、切台、侧键音量、Surface 重绑、后台停流、文件 URI 和截图取消。

## 复核命令

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :backend:importer:testDebugUnitTest :backend:catalog:testDebugUnitTest \
  :backend:playback:testDebugUnitTest --rerun-tasks --console=plain

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :backend:importer:lintDebug :backend:catalog:lintDebug :backend:playback:lintDebug \
  :app:assembleLiveDebug --console=plain
```

审查中的竞态和失败路径依据实际代码确认，尚未为这些新问题执行新增回归测试。106 项既有测试通过不能替代这些补测。
