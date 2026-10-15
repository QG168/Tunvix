# MP2 音频与 EPG 节目单

本轮开发包含音频兼容、节目单及首帧耗时数字样式；已按用户通知更新为 0.2.8（versionCode 11）并完成正式 live 安装包构建。

## 音频

`backend/audio-decoder` 使用 AndroidX Media3 1.11.1 官方 FFmpeg 音频扩展源码，配套 FFmpeg 6.0.1 源码归档。仅启用 MP2 解码器，不启用视频、网络或其他音频解码器。`FfmpegLibrary` 中 `audio/mpeg-L2` 映射改为 `mp2`，其他格式因未编入对应 FFmpeg 解码器继续使用系统 MediaCodec。播放器通过 `EXTENSION_RENDERER_MODE_PREFER` 优先采用 MP2 软件解码，避免设备宣称支持 MP2 却无法正确播放。截图播放器仍独立且静音。

构建时由独立 Android library 的 CMake/ExternalProject 编译 FFmpeg 和 JNI，然后随 live APK 打包。无需用户安装系统解码器。固定 NDK 28.2.13676358 / CMake 3.22.1，编译最低 API 29，JNI 使用 16KB ELF 页对齐。首次打包需要准备这两项 SDK 组件和主机 make；目前 SDK 尚未安装 NDK/CMake，本轮按用户要求未执行安装、编译或打包。

官方来源：https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg

FFmpeg 来源：https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz

FFmpeg 源码归档 SHA-256：`9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623`。保留完整归档用于重建；许可证随 library assets 合并进入正式 APK。官方源码的音频部分保持原有版权声明，只调整 MP2 映射及本项目构建配置；未引入实验性视频 renderer。

## 节目单

管理 → EPG 节目单提供二级订阅管理，支持新增、修改自定义 XMLTV XML/XML.GZ 地址、设为优先与确认后删除。内置 CCSH 地址只读且不可删除。新添加的源排在第一位，可手动指定其他自定义源优先；其余源按显示顺序依次补缺，内置源最后补缺。

内置订阅：https://raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/e.xml.gz

播放页为频道 / 节目单 / 遥控器三个页签。节目单按设备时区显示时间，默认今天，提供源实际包含的其他日期，并突出正在播出的节目。节目列表仅供查看，不提供回看播放。tvg-id 优先精确匹配，其次频道别名匹配；可以选择节目单频道或恢复自动匹配。贵州顺序为卫视、公共、影视、大众、生态、科教、经济；不存在的频道数据不伪造。

配置、频道映射和 XML 缓存独立存放在应用私有 epg 目录。网络和 XML 处理均在 IO 调度器运行。启动或进入节目单时检查六小时有效期，也可主动刷新。刷新成功后原子替换缓存；失败保留上次有效缓存并在设置显示原因。支持 GZIP 内容识别及 HTTP GZIP 包裹，限制解压 XML 最大 32MB / 有效节目最多 200000 条，拒绝 DTD 声明。添加源时先保存订阅，下载错误不会丢失订阅配置。

## 本轮检查范围

仅查看代码、模块依赖、原生构建配置和调用路径；按用户要求未运行测试、编译、模拟器或真实播放验证。安装后重点由用户确认：示例源 MP2 声音、普通/小窗/息屏听音、其他 AAC 频道、节目单频道匹配、订阅优先及删除确认、网络失败后已有缓存和视频播放。

## 0.2.8 打包记录

已准备官方 NDK 28.2.13676358 / CMake 3.22.1，修正音频模块注解依赖和 FFmpeg version 文件与 libc++ 头文件的冲突。`:app:assembleLiveDebug` 构建成功，交付 `artifacts/Tunvix-0.2.8.apk`；四个 ABI 的 MP2 JNI 库已随 APK 打包。未运行测试或模拟器，也未进行实际播放验证。
