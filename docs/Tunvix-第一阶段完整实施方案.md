# Tunvix 第一阶段完整实施方案

更新时间：2026-10-07。本文记录目前实际实现及本次修复方案，供后续开发对话共同遵循。产品范围以《Tunvix-PRD.md》为准；早期 UI 演示验收文档保留为历史记录。

## 当前交付状态

本轮 0.1.1 的最新调整见 [频道与截图调整](本轮频道与截图调整.md)，包括按需自建源、移动网络整组截图、订阅刷新反馈及播放页样式修复。下方旧交付验证记录不能视为本轮新功能的真机验收。

正式 `live` 已接入真实订阅/文件导入、目录持久化和 Media3 播放，不再是空源 UI 演示版本。用户在小米 14 安装后确认“能用了，也能正常播放了”。这是用户实际使用反馈，不代表所有页面、所有直播源或所有 Android 版本均已完成测试。后续真机功能测试由用户负责。

当前交付为个人安装用 debug 签名 APK，版本 0.1.1（versionCode 2），包名 `com.tunvix.app`。旧交付包初次安装只有空的“自建频道”；当前版本改为首次手动保存才创建。没有内置直播源。

## 平台与界面

- Kotlin、Jetpack Compose、Material 3；最低 Android 10（API 29），优先适配小米 14 / 澎湃 OS。
- 灰白背景、蓝紫强调色；首页每行两个 16:9 频道卡片，内容根据窗口宽度自适应。
- 竖屏播放页：上方视频，下方频道列表/遥控器左右切换。用户主动进入横屏全屏，退出恢复竖屏。
- 桌面图标与关于页使用项目根目录 `Tunvix 品牌字标.png`，资源为 `app/src/main/res/drawable-nodpi/app_icon.png`，保持原图比例。
- 首页左上角 Tunvix 字标是纯展示，不响应点击或长按。演示界面索引保留在 demo 的关于页。
- 底栏频道、收藏、最近：在背景和 clickable 之前应用 `clip(RoundedCornerShape(18.dp))`，背景与点击波纹都受圆角裁剪。只给 background 设置圆角不会裁剪波纹，不能退回原实现。

## 真实模块装配与数据流

`app/src/live/kotlin/com/tunvix/app/ServiceFactory.kt` 在应用上下文中创建：

| 能力 | 实现 | 责任 |
| --- | --- | --- |
| A 导入 | AndroidSourceImporter | HTTP/HTTPS 订阅、系统文件 URI、M3U/M3U8/TXT 解析 |
| B 目录 | LocalCatalogRepository | 多源、分组、手动频道、收藏、历史、设置和截图缓存 |
| C 播放 | Media3PlaybackController | 播放状态、Surface、重试、系统音量和生命周期 |
| 界面状态 | TunvixModel | 页面导航、表单、导入确认、源切换及模块调用 |

共享契约位于 `core/contract`。UI 不直接读写目录文件，不另建一套播放器或解析器。`AppServices` 传递 ready/error 流与 catalog 关闭函数；`backendReady=true` 仅由真实 live 装配设置。

导入流程：填写或选择文件 → importer.inspect → 显示解析报告 → 用户确认 → catalog.commitImport → 首页展示。手动频道统一保存到“自建频道”。多个源由用户手动切换；订阅手动刷新，失败保留原有目录。兼容 APTV 使用的标准播放列表地址，不将任意私有订阅格式视为已经支持。

HTTP 已获得用户明确许可，Manifest 的 `usesCleartextTraffic=true` 只影响 Tunvix 应用；HTTPS 同样支持。M3U 中解析出的请求头传入播放器。媒体类型识别包含 HLS/渐进式解析回退，不只依赖 URL 后缀。

目录初始化期间展示加载状态；读取失败展示错误并阻止使用空目录覆盖旧数据。持久化恢复、备份和刷新并发保护详见 `BC-修复验收.md` 与 backend-catalog 文档。

## 输入法修复方案

旧通用输入框使用自绘 BasicTextField 和外层 padding/decoration；用户报告点击无法唤起输入法。此前替换通用 `Field` 为 Material 3 `OutlinedTextField` 后，系统搜狗可用，但用户反馈微信输入法仍无法展开。当前进一步改为 `AndroidView` 承载原生 `EditText`，保留表单样式，直接使用平台文本输入连接，并在获得焦点或再次点击时请求键盘。详情见 [本轮输入法与播放亮屏调整](本轮输入法与播放亮屏调整.md)。未单独真机定位旧组件内部触发点，不把故障归因于手机输入法。

实现位置：`app/src/main/kotlin/com/tunvix/app/ui/Components.kt`。

- value/onValueChange 继续由 TunvixModel 或弹窗状态管理。
- 名称为单行，订阅/播放链接支持多行；保留白色填充、14dp 圆角、聚焦描边与语义标签。
- 此通用组件覆盖订阅名、链接、手动频道、编辑源及新建分组。
- Activity 保持 `android:windowSoftInputMode="adjustResize"`。
- 根布局统一处理 `imePadding()`；表单内容可滚动，固定操作按钮随可用高度上移。
- 不添加全屏透明点击层覆盖输入框，不在普通重组时清除焦点，不用固定键盘高度替代系统 inset。

## 小米底部手势条的全局融合方案

目标：手势条由系统绘制，其后方连续显示当前页面背景；按钮和文字避开系统手势区域。浅色页面使用深色手势标识，横屏全屏使用深色视频背景及系统沉浸模式。

实现位置：`MainActivity.kt`、`res/values/styles.xml`、`ui/Overlays.kt`。

1. `enableEdgeToEdge()` 的状态/导航栏使用透明 `SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { false }`。移除旧的固定白色导航栏样式，避免灰白页面底部出现白色分区。
2. 非全屏根 Column 先绘制 bottomColor，再应用 IME 和底部 navigationBars inset；背景覆盖手势区，内容保留安全距离。取消独立导航栏 Spacer，不在各页面叠加导航栏留白。
3. 首页、收藏、最近、竖屏播放器底部为白色；管理、添加、表单、结果、设置、关于页为 Design.Background。颜色跟随页面，不能全局强制一种白色。
4. 通过 WindowInsetsControllerCompat 更新状态栏和导航栏图标明暗，关闭导航栏强制对比度遮罩。主题中同时声明透明状态/导航栏，保证初始窗口配置一致。
5. Dialog 有自己的 Window。`TransparentDialogBars()` 对底部面板、确认弹窗和分组弹窗设置透明导航栏、关闭对比遮罩并使用深色标识；底部面板自身白色背景配合 navigationBarsPadding 延伸至手势区。
6. 横屏全屏隐藏系统栏，保留滑动临时呼出能力；退出后显示系统栏、恢复竖屏。不要用隐藏手机手势条来替代普通页面的背景融合。

这里使用 Android 公共 API，无需手机系统修改或小米私有反射。手势标识的具体形状和系统输入法窗口外观由系统负责，应用负责自己的窗口背景与安全间距。

官方参考：

- [Android Compose 边到边显示与输入法配置](https://developer.android.com/develop/ui/compose/system/setup-e2e)
- [Android 窗口 inset](https://developer.android.com/develop/ui/compose/system/insets)
- [小米澎湃 OS 适配技术指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2043)

## 播放与资源生命周期

- 上/下一台按当前频道所属源、当前播放分组的列表顺序循环。
- 遥控器音量控制 STREAM_MUSIC，与侧面音量键一致；前台定期读取实际音量更新 UI。
- 返回或切离播放页面停止当前播放；Activity.onStop 通知播放器进入后台，不继续拉流。onStart 通过模型恢复前台状态。
- 手动暂停、音频焦点丢失及耳机断开有独立暂停语义，迟到的播放器回调不得强制自动播放。
- 播放失败页刷新源后从更新目录查找频道再播放，避免使用旧地址。刷新完成时若应用在后台，只暂存恢复请求；再次进入前台且导航代次未改变时才重播。
- ViewModel 清理时释放播放器并关闭目录；目录关闭使用 finally，避免播放器异常导致资源泄漏。
- 首页请求当前分组整组截图，收藏/最近请求展示集合；移动网络与 Wi-Fi 均支持。进入播放或后台停止抓图。最新未打包调整采用 3 并发、8 秒总超时、持久磁盘截图与 12 MiB 内存缓存，见 [缩略图并发与稳定显示](缩略图并发与稳定显示.md)。

## 构建与安装

需要 Android Studio、兼容 AGP 9.1.1 的 JDK、Gradle 9.3.1、Android SDK 37 / build tools 37.0.0。此电脑使用 Android Studio 自带 JBR。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleLiveDebug
```

完整模块单测和静态检查可由开发者按需执行：

```sh
./gradlew :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest :backend:importer:testDebugUnitTest :backend:catalog:testDebugUnitTest :backend:playback:testDebugUnitTest :app:lintLiveDebug
```

正式产物为 `app/build/outputs/apk/live/debug/app-live-debug.apk`；交付副本为 `artifacts/Tunvix-0.1.1.apk`。用户在手机文件管理“下载”目录手动覆盖安装。使用同一签名和包名可以保留已有应用数据，不需要卸载旧版本。

`-PuiOnly=true` 只构建 demo，并禁用 live。正式构建不可加此参数。demo 包名 `com.tunvix.app.demo`，拥有独立演示资源和内存数据，不用于真实直播验收。

## 验收记录与剩余边界

- 本次最终 APK 的 assembleLiveDebug 已构建成功。
- 后台刷新播放风险有新增回归测试，已观察修复前失败、修复后通过；本轮此前 ABC/UI 单测与 live lint 已通过。输入法/手势栏最终改动只做构建，未重复设备测试。
- 用户确认当前版本可用、可正常播放。未据此声称所有公开订阅、所有格式和全部设备兼容性通过。
- Android 10 模拟器测试曾出现测试夹具内容提供者的运行时依赖问题及媒体解码问题，完整设备测试尚未通过；不可写成验收全部成功。
- 旧交付包 HLS 卡片使用 logo/占位回退。本轮代码已加入 Media3 HLS 画面提取，尚未统一打包及真机验收；失败仍回退 logo/占位。
- 真机后续由用户检查：不同输入框与长链接、键盘收起/切换、各页及弹窗底部融合、源刷新/切换、分组循环、音量键同步、前后台、横竖屏，以及坏链接错误提示。
