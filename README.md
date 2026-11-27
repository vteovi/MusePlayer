# MusePlayer

一款使用 Kotlin + Jetpack Compose + Media3（ExoPlayer）实现的 Android 本地 / 局域网视频播放器，界面与交互参考 MX Player。内置 Media3 与 libVLC 双播放引擎：常规视频走系统硬解，遇到系统解不了的格式（如 10bit HEVC）自动切到 libVLC 软解续播。

[![Build & Release APK](https://github.com/vteovi/MusePlayer/actions/workflows/release.yml/badge.svg)](https://github.com/vteovi/MusePlayer/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/vteovi/MusePlayer)](https://github.com/vteovi/MusePlayer/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 功能

### 媒体库
- 扫描本地视频，支持视频 / 文件夹两种视图，网格 / 列表两种排列（首次打开默认列表视图）
- 正方形缩略图，仿系统相册裁切
- 按日期、名称、时长、大小、文件夹排序，支持搜索，排序方式持久化
- 隐藏文件夹管理，可显示系统隐藏目录（含 `.` 开头的目录），隐藏视频时长后台补齐
- 长按文件或文件夹弹出操作菜单（播放全部 / 随机播放 / 从头播放 / 查看信息 / 复制路径 / 打开所在文件夹）
- 视图模式与排序偏好会持久化，下次打开保持上次状态

### 浏览
- 文件管理器式逐级浏览，返回键退回上一级
- 支持内部存储、SD / TF 卡以及 U 盘等外接存储，可在右上角切换存储卷
- 空目录或无权限目录会给出明确提示
- 点击视频以当前目录为播放队列

### 网络与流媒体
- SMB（jcifs-ng）、FTP（commons-net）、WebDAV（OkHttp）服务器浏览与播放
- 局域网服务器自动发现，无需手动填写地址
- 支持 HTTP(S)、HLS（m3u8）、DASH、RTSP 等流媒体地址
- 可被其他应用以「打开方式 / 分享」调起，也可通过 `muse://play?url=<地址>` 深链播放
- 兼容 MX Player 的 `title` / `video_list.name` 标题扩展
- SMB 等网络播放使用预读缓冲与磁盘缓存，减少卡顿

### 播放器
- 手势调节亮度（左半屏）、音量（右半屏）、进度（横向滑动），进度手势带画面预览
- 双击快进 / 快退（秒数可设置），长按倍速播放（2x–8x 可设置）
- 播放倍速、常见画面比例切换（含 9:16 竖屏比例），固定比例直接拉伸画面本身
- 画面可旋转、捏合缩放，缩放比例按视频分别记忆并持久化
- 自动匹配同目录字幕（SRT / ASS / SSA / VTT / TTML）
- 画中画（PiP）播放，进入后回到桌面，关闭小窗立即停止播放
- 后台播放（MediaSessionService），通知栏与蓝牙耳机控制，通知播放卡片可关闭
- 循环模式（关闭 / 列表循环 / 单曲循环）与随机播放状态持久化，切换视频自动恢复
- 播放历史与断点续播，包含本地与网络播放记录，并显示文件路径或流媒体地址
- 状态栏、导航栏沉浸式显示，系统栏区域铺黑
- 解码失败自动切换 libVLC 软解（仅完整版）

### 界面与设置
- 手机竖屏使用底部导航栏，平板 / 横屏使用左侧导航栏，选中仅图标与文字变色
- 深色模式，OLED 纯黑背景
- 低性能模式：不加载视频缩略图，老设备可显著减轻滚动负担
- 可调项：双击快进秒数、左右滑动调进度范围、长按倍速、自动字幕、网格 / 列表视图等
- 关于页面展示版本、开源信息与第三方许可

## 下载

前往 [Releases](https://github.com/vteovi/MusePlayer/releases) 下载。完整版按 CPU 架构拆分，请按设备选择：

| 文件 | 说明 |
| --- | --- |
| `MusePlayer_vX.Y.Z_arm64-v8a.apk` | 绝大多数现代手机 / 平板（首选） |
| `MusePlayer_vX.Y.Z_armeabi-v7a.apk` | 32 位老设备 |
| `MusePlayer_vX.Y.Z_x86_64.apk` | 模拟器 / x86 平板 |
| `MusePlayer_Lite_vX.Y.Z.apk` | 轻量版，不含 libVLC，体积小，可与完整版共存安装 |

- 最低支持 Android 7.0（API 24）
- 完整版约 56–67 MB（含 libVLC 原生库），轻量版约 17 MB
- 首次启动需要授予视频读取权限；浏览 SD 卡 / U 盘或系统隐藏目录时，需要「所有文件访问权限」
- 轻量版包名为 `com.muse.player.lite`，无法播放系统不支持的格式（如 10bit HEVC），会给出明确提示

## 从源码构建

环境要求：

- JDK 17
- Android SDK 34（compileSdk / targetSdk 34）
- Gradle 由仓库自带 wrapper 提供

项目使用 `full` / `lite` 两个 flavor，需分别构建：

```bash
# Windows
$env:JAVA_HOME="C:\Java\jdk-17"
.\gradlew.bat :app:assembleFullRelease
.\gradlew.bat :app:assembleLiteRelease

# macOS / Linux
./gradlew :app:assembleFullRelease
./gradlew :app:assembleLiteRelease
```

产物位置：

- 完整版：`app/build/outputs/apk/full/release/`
- 轻量版：`app/build/outputs/apk/lite/release/`

Windows 下可以直接双击根目录的 `build.bat`（内部调用 `build.ps1`），会构建两个 flavor、执行 lint，并把 APK 以 `MusePlayer_v<版本>_<abi>.apk` / `MusePlayer_Lite_v<版本>.apk` 复制到项目根目录，结束后自动清理 Gradle 守护进程与残留 JVM。

### 签名说明

本项目为个人开源项目，release 包使用仓库内的 `signing/debug.keystore`（Android 调试签名）签名，本机构建与 GitHub Actions 构建的签名一致，可直接覆盖安装。该签名仅用于分发便利，不适用于应用商店。

## 持续集成

`.github/workflows/release.yml` 会在推送 `v*` 标签（或手动触发）时自动：

1. 使用 JDK 17 构建完整版与轻量版
2. 完整版按 ABI 重命名为 `MusePlayer_v<版本>_<abi>.apk`，轻量版输出单个 `MusePlayer_Lite_v<版本>.apk`
3. 上传构建产物，并创建对应的 GitHub Release 附带全部 APK

## 技术栈

- Kotlin 1.9.24 / AGP 8.5.2 / Gradle 8.7 / JDK 17
- Jetpack Compose（BOM 2024.06.00，Material 3）、Navigation Compose
- Media3 / ExoPlayer 1.4.1：MediaSessionService、自定义 DataSource、HLS / DASH / RTSP
- libVLC 3.6.0：第二播放引擎，强制软解，作为解码兜底（仅完整版）
- jcifs-ng（SMB）、commons-net（FTP）、OkHttp（WebDAV）
- ABI split：arm64-v8a / armeabi-v7a / x86_64

## 目录结构

```
app/src/main/java/com/muse/player
├── data/          媒体库扫描、历史、设置、缩放记忆、字幕匹配、网络客户端与局域网发现
├── playback/      Media3 播放服务、SMB / FTP / WebDAV 数据源
├── ui/            Compose 界面：媒体库、浏览、网络、播放器、设置、关于
└── util/          格式化、权限、缩略图与进度预览加载等工具

app/src/full/java    libVLC 引擎真实实现（仅完整版编译）
app/src/lite/java    同 API 的空实现与占位类（轻量版使用）
```

## 版本与更新记录

- 版本遵循语义化版本：MAJOR 为架构级变更，MINOR 为新功能，PATCH 为修复与微调
- 更新记录见 [CHANGELOG.md](CHANGELOG.md)
- 分支约定：`dev` 为日常开发分支，发布时合并到 `master` 并打 `vX.Y.Z` 标签；实验性改动单独存放于 test 分支

## 许可证

[MIT](LICENSE)

本项目仅用于学习与个人使用，请勿用于传播未经授权的内容。
