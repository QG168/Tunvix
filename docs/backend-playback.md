# backend:playback 交付文档

对话 C · 播放器与系统音量 · 2026-10-07

## 公开入口与依赖

- 公开类：`com.tunvix.backend.playback.Media3PlaybackController`
  - 前端唯一装配入口：`Media3PlaybackController.create(context)`，在 `AppServices` 工厂中赋给 `PlaybackController` 接口。
  - `create` 内部使用 `context.applicationContext`，不持有 Activity。
- 依赖：仅 `:core:contract` + Media3（`media3-exoplayer` / `media3-exoplayer-hls` / `media3-datasource` / `media3-common` 1.11.1）+ `kotlinx-coroutines-android`。
- 模块为 Android library，`compileSdk=37`、`minSdk=29`，与根工程约定一致；由 `settings.gradle.kts` 的自动探测包含（检测到 `backend/playback/build.gradle.kts` 即生效）。
- 未修改 `app/`、根 Gradle、契约与其他模块的任何文件。

## 已实现能力（对照任务要求）

1. **播放能力**：HTTP/HTTPS HLS 与常见渐进式媒体；每频道 `Channel.headers` 通过动态 `DataSource.Factory` 注入（`User-Agent` 单独处理，其余作为默认请求头；连接/读取超时 10s，允许跨协议重定向）。
   - **格式选择不凭后缀**：无扩展名地址先按渐进式探测，内容读不出（`UnrecognizedInputFormatException`）时强制按 HLS 重试；带 m3u8 提示但解析失败时反向回退渐进式。每个准备周期最多回退一次。
2. **Surface**：`attachSurface/detachSurface` 绑定 UI 的 `SurfaceView`；全程单一 `ExoPlayer` 实例，重绑定不重建播放器、不重连；通过 `onVideoSizeChanged` 发布真实比例到 `snapshot.videoAspectRatio`（含 `pixelWidthHeightRatio`）。
3. **状态发布**：`IDLE/CONNECTING/PLAYING/PAUSED/FAILED`，失败原因区分网络、地址失效（`mayBeExpired=true`，提示刷新直播源）、格式不支持、解码失败、流中断、未知。
4. **快速切台**：每次 `play()` 递增频道代次，每次 `prepare` 递增准备序号；引擎回调携带序号，旧频道的任何回调（含错误、ready）都会被丢弃，不可能覆盖新频道状态。`retry()` 只重连当前频道。
5. **断流重试**：仅网络类/流中断错误自动重试，最多 3 次、退避 500ms→1000ms→1500ms；重试期间快照提示「正在重连（n/3）」；预算在出画面后重置；成功出画面即停止。**绝不自动切台**。
6. **生命周期**：
   - `onBackground`：停止拉流（`player.stop()`），区分两种暂停：用户主动暂停 → 前台恢复后仍停在暂停态；播放中直接后台 → 前台自动重连并回到直播最新位置。
   - `onForeground`：按上述两种语义重新拉流（用户主动暂停时 prepare 后保持暂停）。
   - `stop`：停止拉流并作废一切在途回调，回到 `IDLE`（保留 `channelId` 供 UI 展示）。
   - `release`：取消协程、注销音量/焦点/耳机监听、释放播放器，无泄漏回调与 Surface 引用。
7. **音量**：`AudioManager STREAM_MUSIC` 控制，百分比 ↔ 真实档位四舍五入映射，`setVolume` 收敛到 [0,100] 与 [0,max]，不越界；通过 `ContentObserver(Settings.System)` 监听系统音量变化（覆盖音量侧键），变化即刷新 `snapshot.volumePercent`。
8. **音频焦点**：`AudioFocusRequest(AUDIOFOCUS_GAIN, USAGE_MEDIA)`；瞬时丢失暂停、焦点恢复自动续播；永久丢失只暂停、需用户手动恢复。耳机断开（`AUDIO_BECOMING_NOISY`）按系统暂停处理，不覆盖用户暂停标记。

## 验证

- 命令：`./gradlew :backend:playback:testDebugUnitTest :backend:playback:lintDebug`（24 个用例全部通过；lint 0 错误，仅 2 条 NewerVersionAvailable 版本提示）+ `./gradlew :backend:playback:assembleDebug`（AAR 构建通过）
- 本地单测（假引擎 + 假音量/焦点，覆盖）：
  - 状态流：play→CONNECTING→PLAYING、pause/resume；
  - 旧回调隔离：切台后旧频道错误/ready 被丢弃；
  - 重试：3 次退避重连后 FAILED、不可恢复错误立即 FAILED 且 `mayBeExpired=true`、`retry()` 只针对当前频道、FAILED 后 resume 等价重试；
  - 生命周期：后台停拉流/前台回直播、用户暂停跨后台/前台不被覆盖、stop 后回调作废；
  - 音量：百分比↔档位映射边界（含 max=0 防御）、侧键变化刷新快照；
  - 焦点：瞬时丢失可自动恢复、永久丢失不自动恢复；耳机断开暂停；画面比例更新。

## 审查修复记录（2026-10-07，对应 docs/ABC-交付审查.md「C 必须修复」）

1. **P1 后台自动重连继续拉流**：`onBackground` 现在立即取消退避重连任务；`onEngineError` 在后台挂起态直接忽略错误（不重试、不置失败），重连任务的执行条件增加 `!suspendedByBackground` 双保险。新增回归测试：退避期间进后台不再拉流、回前台仅恢复一次并回到直播位；后台到达的错误回调被忽略；用户暂停意图跨后台/前台保留。
2. **P2 焦点申请被拒仍播放**：新增 `acquireFocus()`，play / retry / resume / onForeground / 退避重连统一使用；被拒时按「暂时焦点丢失」处理——prepare 完成后保持暂停不自动出声，待 GAINED 自动续播。新增回归测试：play / retry / resume 三处申请被拒均不出声。
3. **lint UnsafeOptInUsageError（8 处）**：`Media3PlayerEngine` 类级声明 `@androidx.annotation.OptIn(UnstableApi::class)`，未关闭 lint、未建 baseline。`lintDebug` 通过：0 错误、2 条 NewerVersionAvailable 提示（Media3 1.11.1 已是当时稳定版，提示来自 Google Maven 更新通道，非阻断）。
- 错误映射（`PlaybackErrors`）与音量映射（`VolumeMapper`）为纯函数，已含边界用例。

## 限制与已知事项

- **真机项待验收**（本模块无真机环境）：音量侧键与遥控器同步、横竖屏 Surface 重绑定、Wi-Fi/移动网络切换。真机路径：小米 14 + Android 10（见 PRD A08/A10）。
- 格式回退逻辑（无后缀 HLS）在真机上以真实无扩展名直播源验证；解析失败回退最多一次，若两种实现都失败则返回 FORMAT 错误，不声称所有源都能播。
- 后台进入即停拉流（PRD 无后台播放/画中画需求），未实现后台服务。
- 暂停期间不主动断流（仅 `stop`/后台才停）；如需「暂停也断流」由前端在 `onBackground` 语义外另行提出。
- 日志（Log.w）只输出错误码名与 HTTP 状态，不含 URL / token。
- 用户点恢复会重新申请焦点；申请失败保持暂停，不自动出声。永久焦点丢失与耳机断开需用户主动恢复。

## 集成提示（供前端对话）

- `attachSurface` 建议在 Composable 进入组合时调用、`detachSurface` 在离开时调用；横屏全屏切换只需 detach + attach 新 SurfaceView，无需 `stop/play`。
- 播放前确认移动网络提示逻辑归前端；本模块不感知网络类型。
- `retry()` 对应播放失败 UI 的「重试」按钮；`resume()` 在 FAILED 态等价 retry。
- `snapshot.videoAspectRatio` 变化时按等比适配视频输出区域。

## 前端负责人的直接修复（2026-10-07）

- 新增 systemPaused，永久焦点丢失/耳机断开不会因 READY、PLAYING、GAINED、退避或前后台恢复自动出声。主动选台、重试和恢复会清除永久暂停原因。
- 焦点丢失取消退避；退避捕获准备代次，执行前核对所有暂停原因。
- 用户暂停后的迟到错误不自动重连，但记录 needsPrepare，主动恢复时重新准备实际引擎，避免卡在 CONNECTING。
- 连续后台/前台切换不会清除用户暂停意图；临时焦点恢复如遇错误也会重新准备。
- 新增 7 项回归测试，均在修复前观察到失败，修复后通过。playback 总计 31 项测试。
- 真实音频焦点、Surface、侧键音量等仍待设备验收。
