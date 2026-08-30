# 音澜（Melody Local）

一个使用 Kotlin 与 Jetpack Compose 编写的原生 Android 本地音乐播放器。歌曲、视频音轨提取、歌单和歌词处理均在设备本地完成。

当前版本：`1.3.0`（Android `versionCode` 5）。本版本增加视频音轨导入和可恢复的歌单文件汇总，并保留明亮界面与无卡顿播放模式切换。

[下载最新版本](https://github.com/nishijie6/melody-local/releases/latest) · [隐私说明](PRIVACY.md) · [变更记录](CHANGELOG.md)

## 已实现功能

- 通过 `MediaStore` 扫描设备本地歌曲，支持 Android 8.0–16
- 按标题、歌手或最近添加排序，并支持歌名/歌手/专辑搜索
- 基于 Media3 ExoPlayer 的后台播放、系统媒体通知与耳机控制
- 播放/暂停、上一首、下一首与进度拖动
- 五种互斥播放模式：播放一次、列表循环、随机循环、单曲循环、倒序循环
- 随机播放使用 Media3 的随机遍历顺序，每轮每首歌曲只出现一次，上一首会沿当前随机顺序回退
- 倒序播放会从当前列表末尾向前循环；切换模式时保留当前歌曲和播放进度
- 切换顺序、列表循环、单曲循环和倒序模式仅更新播放器遍历策略；随机顺序在后台线程生成，切换时不阻塞界面、唱片动画或播放进度
- 全屏播放器提供明确的停止入口，可停止播放并清空当前队列
- 自动记住上次选择的播放模式
- 迷你播放器与沉浸式全屏播放页
- 全局使用暖白背景、白色卡片和珊瑚色强调的明亮主题，状态栏与导航栏图标保持深色高对比
- 黑胶唱片式旋转封面：播放时匀速旋转，暂停时停在当前角度并在恢复后续转
- 唱针随播放状态自动落下和抬起
- 创建、重命名、删除歌单；向歌单添加或移除歌曲；歌单名称不允许重复
- 使用 Room 在本机持久化歌单
- 导入 `.lrc` 歌词并随播放进度自动滚动、高亮；点击歌词行可跳转
- 支持 LRC 多时间标签、`offset` 偏移、纯文本歌词、UTF-8 和 GB18030 编码
- 通过系统文件选择器选择一个普通、未加密的本地视频，把其中音轨导出为 M4A：AAC 自动直封装，其他设备可解码音轨转为 AAC
- 导入视频前可编辑标题、歌手和专辑；默认把第一个可解码视频帧压缩为唱片封面，失败时使用原有占位封面
- 导出歌曲写入公共 `Music/音澜/视频提取/`，同名文件自动追加 `(2)`、`(3)`，不会覆盖已有文件
- 在歌单页把所有歌单歌曲按 MediaStore ID 去重后真正移动到 `Music/音澜/歌单汇总/`；最后一级目录名可修改
- 同卷歌曲优先保留 MediaStore ID；跨卷歌曲在 SHA-256 和大小校验通过后才删除源文件，并自动重映射所有歌单、歌词、封面和自定义元数据
- 文件移动带持久化事务日志、进度、取消、Android 系统授权等待态与部分完成摘要；进程中断后会清理未提交副本或完成已删除源文件的映射

## 隐私

音澜不申请网络权限，不包含账号、广告、分析或遥测功能。视频只在用户通过 Android 系统选择器明确授权后读取；应用不会扫描 B 站或其他应用的私有缓存，不绕过沙箱、缓存加密或 DRM。由于 MediaStore ID 不能安全跨设备复用，应用数据不会进入 Android 云备份或设备迁移，详情见 [PRIVACY.md](PRIVACY.md)。

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

## 从视频提取歌曲

1. 在“歌曲”页点击右上角的视频图标，并通过 Android 系统文件选择器选取一个你有权使用的本地视频。
2. 编辑标题、歌手、专辑，并选择是否使用视频首帧作为封面。
3. 点击“开始提取”。任务可在前台进度窗口或系统通知中取消；成功后歌曲位于 `Music/音澜/视频提取/`。

音澜只处理系统选择器可访问的普通文件。无音轨、DRM、不可访问、设备无法解码或空间不足会明确失败，并清理临时文件和未发布的 MediaStore 项目。

## 汇总所有歌单歌曲

1. 打开“歌单”页右上角批量菜单，选择“汇总歌单歌曲”。
2. 确认去重后的歌曲数、总大小和目标文件夹名称。
3. 阅读“原位置将不再保留”警告后确认移动，并按系统提示授权。

Android 10 会逐首请求授权；Android 11–16 最多每 2000 首组成一个系统授权批次；Android 8–9 只在开始移动时请求旧版存储写入权限。拒绝或取消不会撤销已成功完成的歌曲，尚未删除源文件的新副本会安全清理。

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
├── media/      # 视频音轨导入、MediaStore 文件移动与恢复状态机
├── playback/   # Media3 播放服务与控制连接
├── ui/         # Compose 页面、组件和 ViewModel
└── MainActivity.kt
```

## 验证

```powershell
.\gradlew.bat testReleaseUnitTest lintRelease assembleRelease assembleDebugAndroidTest
```

连接模拟器或真机后运行 Android 集成测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

当前工程包含 69 项自动化测试：32 项 JVM 单元测试覆盖 LRC、播放策略、歌单约束、AAC 导出决策、目录和文件名规则、去重、移动路线、SHA-256 校验与恢复决策；37 项 Android 集成测试覆盖 MediaStore 映射、Media3、Room 1→2→3 迁移、多歌单 ID 重映射、元数据/移动日志事务、ViewModel 授权和部分完成状态，以及真实 Transformer 的音频输出、取消清理与无音轨失败。CI 在 API 26、29、36 模拟器执行设备测试。

## 文档

- [ARCHITECTURE.md](ARCHITECTURE.md)：模块边界、数据流、状态归属和关键设计选择
- [CONTRIBUTING.md](CONTRIBUTING.md)：开发环境、测试、提交和签名配置
- [PRIVACY.md](PRIVACY.md)：本地数据、权限、系统备份和删除方式
- [SECURITY.md](SECURITY.md)：漏洞报告方式和签名材料安全要求
- [CHANGELOG.md](CHANGELOG.md)：版本变更记录

## 许可证

本项目使用 [MIT License](LICENSE)。
