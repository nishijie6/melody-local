# 音澜（Melody Local）

一个使用 Kotlin 与 Jetpack Compose 编写的原生 Android 本地音乐播放器。应用只读取设备上的音频，歌单和导入的歌词均保存在本机。

当前版本：`1.1.0`（Android `versionCode` 2）。

[下载最新版本](https://github.com/nishijie6/melody-local/releases/latest) · [隐私说明](PRIVACY.md) · [变更记录](CHANGELOG.md)

## 已实现功能

- 通过 `MediaStore` 扫描设备本地歌曲，支持 Android 8.0–16
- 按标题、歌手或最近添加排序，并支持歌名/歌手/专辑搜索
- 基于 Media3 ExoPlayer 的后台播放、系统媒体通知与耳机控制
- 播放/暂停、上一首、下一首与进度拖动
- 五种互斥播放模式：播放一次、列表循环、随机循环、单曲循环、倒序循环
- 随机播放使用 Media3 的随机遍历顺序，每轮每首歌曲只出现一次，上一首会沿当前随机顺序回退
- 倒序播放会从当前列表末尾向前循环；切换模式时保留当前歌曲和播放进度
- 全屏播放器提供明确的停止入口，可停止播放并清空当前队列
- 自动记住上次选择的播放模式
- 迷你播放器与沉浸式全屏播放页
- 黑胶唱片式旋转封面：播放时匀速旋转，暂停时停在当前角度并在恢复后续转
- 唱针随播放状态自动落下和抬起
- 创建、重命名、删除歌单；向歌单添加或移除歌曲；歌单名称不允许重复
- 使用 Room 在本机持久化歌单
- 导入 `.lrc` 歌词并随播放进度自动滚动、高亮；点击歌词行可跳转
- 支持 LRC 多时间标签、`offset` 偏移、纯文本歌词、UTF-8 和 GB18030 编码

## 隐私

音澜不申请网络权限，不包含账号、广告、分析或遥测功能。应用只读取 Android 媒体库中的音频，并把歌单、播放模式和用户主动导入的歌词保存在本机。由于 MediaStore ID 不能安全跨设备复用，应用数据不会进入 Android 云备份或设备迁移，详情见 [PRIVACY.md](PRIVACY.md)。

## 安装

1. 从 [GitHub Releases](https://github.com/nishijie6/melody-local/releases/latest) 下载最新的 `yinlan-*-release.apk`。
2. 在 Android 设备上允许从当前来源安装应用。
3. 安装后授予音乐读取权限；Android 13 及以上还可授予通知权限，以显示后台播放控制。

## 使用歌词

1. 开始播放一首歌曲，并点击底部迷你播放器。
2. 在全屏播放页切换到“歌词”。
3. 点击“导入 LRC”，选择与当前歌曲对应的 `.lrc` 文件。

歌词会复制到应用的私有目录，因此原始文件被移动或删除后仍可继续显示。可从歌词页右上角菜单更换或移除歌词。

常见的同步歌词格式：

```text
[ar:歌手]
[ti:歌名]
[00:12.40]第一句歌词
[00:18.75]第二句歌词
```

## 构建

环境要求：JDK 17 或更高版本、Android SDK 36。

Windows Debug 构建：

```powershell
.\gradlew.bat assembleDebug
```

Windows Release 构建：

```powershell
.\gradlew.bat assembleRelease
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

Release 变体启用了 R8 代码压缩和资源裁剪。公开仓库不包含 Release 私钥或密码；维护者可复制 `keystore.properties.example` 为 `keystore.properties`，并把私钥放在本机的 `release-signing/` 目录。两者均已加入 `.gitignore`。请离线备份正式证书，否则未来无法覆盖安装使用该证书签名的版本。

## 工程结构

```text
app/src/main/java/com/melody/local/
├── data/       # MediaStore 曲库、Room 歌单数据库
├── lyrics/     # LRC 解析与本地歌词存储
├── playback/   # Media3 播放服务与控制连接
├── ui/         # Compose 页面、组件和 ViewModel
└── MainActivity.kt
```

## 验证

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

连接模拟器或真机后运行 Android 集成测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

当前工程包含 52 项自动化测试：24 项 JVM 单元测试覆盖 LRC 解析与资源边界、原子文件替换/降级、播放策略和歌单竞争校验；28 项 Android 集成测试覆盖 MediaStore 映射、Media3 服务与冷启动控制链、Room 迁移/约束/文件单例、ViewModel 状态边界、歌词持久化/并发、权限流程和歌词导入目标绑定。Debug/Release APK 全量构建与 Android Lint 均纳入发布验证。

## 文档

- [ARCHITECTURE.md](ARCHITECTURE.md)：模块边界、数据流、状态归属和关键设计选择
- [CONTRIBUTING.md](CONTRIBUTING.md)：开发环境、测试、提交和签名配置
- [PRIVACY.md](PRIVACY.md)：本地数据、权限、系统备份和删除方式
- [SECURITY.md](SECURITY.md)：漏洞报告方式和签名材料安全要求
- [CHANGELOG.md](CHANGELOG.md)：版本变更记录

## 许可证

本项目使用 [MIT License](LICENSE)。
