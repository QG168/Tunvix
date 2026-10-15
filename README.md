# Tunvix

原生 Android 直播播放器，最低 Android 10。已完成 HTML 界面的 Compose 还原，并接入真实导入、持久化与播放模块。用户已在小米 14 确认可用且正常播放。

## 文档

- [第一阶段完整实施方案（当前实现、输入法与全局手势栏适配）](docs/Tunvix-第一阶段完整实施方案.md)

- [产品需求](docs/Tunvix-PRD.md)
- [并行开发任务与接口边界](docs/并行开发与后端任务.md)
- [UI 实施计划](docs/superpowers/plans/2026-10-07-native-ui.md)
- [UI 验收说明](docs/UI-还原与验收.md)

## 构建

Android Studio 打开本目录，使用与 AGP 9.1.1 / Gradle 9.3.1 兼容的 JDK。此电脑使用 Studio 自带 JBR。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleLiveDebug
./gradlew -PuiOnly=true :app:assembleDemoDebug
./gradlew :app:testDemoDebugUnitTest :app:testLiveDebugUnitTest :app:lintLiveDebug
./gradlew -PuiOnly=true :app:connectedDemoDebugAndroidTest
```

`uiOnly` 只用于前端独立构建，禁用 live 并跳过 backend 模块；正式构建和后端模块测试不要添加此参数。

- `demo`：包名 `com.tunvix.app.demo`，只展示设计稿静态图和内存演示数据，不连接直播地址。到关于页打开界面索引；首页 Tunvix 标识不可点击。
- `live`：包名 `com.tunvix.app`，初始没有频道，不含演示图片。已支持真实导入、播放和本地持久化。

后端接入点：`app/src/live/kotlin/com/tunvix/app/ServiceFactory.kt`。已装配 importer、catalog、playback 三个真实模块，`backendReady=true`。
