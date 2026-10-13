# Rhythm Practice Player ｜ 节奏练习播放器

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> A practice-oriented video player built for rhythm game players — watch hand-cam videos at slow speed without pitch change, loop the tricky section with precision, and focus on the chart without any UI distraction.
>
> 面向音游玩家的练习专用视频播放器：变速不变调、高精度 A/B 区间练习、毫秒级精细定位、沉浸式练习模式，让"看手元、拆谱面"的练习流程更高效。

[中文](#中文) ｜ [English](#english)

---

## 中文

### 项目简介

音游玩家（如《Project SEKAI》《BanG Dream!》《Arcaea》等）的练习方式与普通看视频有本质区别：需要**反复观看难点段落**，从视觉和操作两个维度拆解谱面，研究指法、节奏与判定时机。

通用播放器的单项能力（倍速、循环、逐帧）分散且缺少音游特化的交互。本项目将**变速不变调 + 高精度 A/B 区间练习 + 帧级定位 + 沉浸式练习 + 状态记忆**组合为完整的练习流程，真正做到"为练习而设计"。

- **纯本地**：不申请任何权限、不访问网络、不上传任何数据
- **零广告**：无广告、无账号、无遥测（可自行确认代码）
- **Android 10+**（minSdk 29），当前版本 **1.0.3**

### 下载安装

- **官方发布页**：[rhythm.rylum.top](https://rhythm.rylum.top)（推荐，含功能介绍与更新日志）
- **GitHub Releases**：[Releases 页面](https://github.com/RainYlum/RhythmPracticePlayer/releases)（最新 APK 下载）
- 系统要求：Android 10（API 29）及以上

### 功能特性

| 能力 | 说明 |
|------|------|
| 🎵 **变速不变调** | 固定 0.5x / 0.6x / 0.7x / 0.8x / 0.9x / 1.0x 六档慢速，音调不漂移（Sonic 时域拉伸），拆谱不"花栗鼠" |
| 🔁 **A/B 区间练习** | 设置 A/B 点后，播到 B 点**自动暂停并复位到 A 点**，一遍练完回到起点；也可开启**无缝连续循环**反复播放 |
| ⏱ **倒计时预听** | 点击播放后 3-2-1 倒计时：画面定格起点帧、**音频从起点前 3 秒先行进入**帮你跟住节奏，倒计时结束音画对齐起播 |
| 🧘 **沉浸式练习模式** | 播放中画面干净无 UI 干扰，点击/滑动不影响播放；双击左上角退出 |
| 🔬 **帧级精细定位** | 暂停状态 0.1s / 1s 点按步进，拖动进度条时**画面实时跟随预览** |
| 📌 **帧标记（书签）** | 任意帧添加缩略图 + 自定义文本标记，随时跳回；沉浸播放中标记点 ±3s 在顶部**字幕提醒**（如"这里换指法"） |
| 📚 **视频库管理** | 主页视频列表：缩略图、备注编辑、最近进度、删除（关联数据级联清除，源文件不受影响） |
| 💾 **练习状态记忆** | 退出后再次打开同一视频，自动恢复上次位置、倍速、A/B 选区、倒计时配置与音频延迟 |
| 🎧 **音频延迟校正** | ±100ms（5ms 步进）微调音频相对视频的偏移，校正录制造成的音画不同步 |
| 📱 **方向自动适配** | 横屏/竖屏随视频录制方向自动适配，等比完整显示，不裁切不拉伸 |
| 🌗 **现代 Material 3** | 深色/浅色主题 + Android 12+ 动态取色 |

### 技术栈

- **语言/框架**：Kotlin 2.2.10 · Jetpack Compose（Material 3）· Coroutines / Flow
- **播放引擎**：Media3 ExoPlayer 1.10.1（变速保音调、Clipping + Looping、音频处理管线）
- **架构**：MVVM + Repository + 单向数据流（UI → ViewModel → 引擎封装层），播放器接口与 ExoPlayer 解耦
- **存储**：DataStore Preferences + SAF（`content://` Uri 持久授权，全程零存储权限）
- **序列化**：kotlinx.serialization
- **构建**：AGP 9.3.0 · Gradle · minSdk 29 / target & compile SDK 37
- **测试**：JUnit4 · Turbine（Flow 测试）· Mockito · Compose UI Test · 仪器冒烟测试

### 项目结构

```
app/src/main/java/com/rythm/app/
├── ui/          # Compose 界面：主页视频库 / 播放页 / 沉浸模式 / 倒计时层
├── viewmodel/   # PlayerViewModel / LibraryViewModel / BookmarkViewModel
├── domain/      # 纯 Kotlin 规则：LoopPolicy / FrameStepPolicy / LibraryPolicy
├── data/        # Repository：VideoRepository / VideoLibraryRepository / BookmarkRepository
├── player/      # 播放引擎封装层（唯一接触 ExoPlayer 的层）：播放管理 / 选区监听 / 循环源构建 / 倒计时 / 音频延迟
├── model/       # 状态与数据模型
└── util/        # 时间格式化等工具
```

### 构建

环境要求：JDK 17+、Android SDK（compileSdk 37）。使用 Android Studio 打开项目即可运行；命令行方式：

```bash
# Debug（可直接安装）
gradlew.bat assembleDebug          # Windows
./gradlew assembleDebug            # macOS / Linux

# Release（需要签名配置 app/sign/key.properties，该文件不入库）
gradlew.bat assembleRelease
```

Release 产物命名约定：`rhythmplayer-<version>.apk`（如 `rhythmplayer-1.0.3.apk`）。

### 测试

```bash
gradlew.bat testDebugUnitTest              # 单元测试（领域规则、状态折叠、循环/步进策略等）
gradlew.bat connectedDebugAndroidTest      # 仪器冒烟测试（需真机或模拟器）
```

### 隐私

- **零权限**：Manifest 未申请任何运行时权限，不包含 `INTERNET` 权限
- **纯本地**：视频、帧标记、历史记录全部本地存储（DataStore + 文件），无任何网络上传
- **SAF 只读**：通过系统文件选择器访问视频，不写用户目录，卸载即清除

### 许可证

本项目基于 [MIT License](LICENSE) 开源，详见 [LICENSE](LICENSE) 文件。

---

## English

### Introduction

Rhythm game players (e.g. *Project SEKAI*, *BanG Dream!,* *Arcaea*) practice in a fundamentally different way from ordinary video watching: they need to **watch the tricky passage over and over**, dissecting the chart from both visual and execution perspectives — fingering, rhythm and timing.

Generic players provide scattered individual capabilities (speed change, looping, frame stepping) without rhythm-game-specific interaction. This project combines **pitch-preserving speed change + precise A/B section looping + frame-level scrubbing + immersive practice mode + session state memory** into one complete practice workflow — designed for practicing, not just watching.

- **100% local & private**: no permissions requested, no network access, no data upload
- **No ads**: no ads, no accounts, no telemetry (verify it yourself in the source)
- **Android 10+** (minSdk 29), current version **1.0.3**

### Download

- **Official site**: [rhythm.rylum.top](https://rhythm.rylum.top) (features & changelog, recommended)
- **GitHub Releases**: [Releases page](https://github.com/RainYlum/RhythmPracticePlayer/releases) (latest APK)
- Requirements: Android 10 (API 29) or above

### Features

| Capability | Description |
|------------|-------------|
| 🎵 **Variable speed, pitch preserved** | Six fixed speeds: 0.5x / 0.6x / 0.7x / 0.8x / 0.9x / 1.0x. Tempo changes via Sonic time-stretching — no "chipmunk" pitch drift while studying charts |
| 🔁 **A/B section practice** | Set A/B points: playback **pauses and resets to A automatically when reaching B**, ready for the next rep; or enable **seamless continuous looping** |
| ⏱ **3-second countdown preview** | After tapping play: video freezes on the start frame while **audio starts 3 seconds earlier**, so you lock onto the rhythm; audio and video align exactly when the countdown ends |
| 🧘 **Immersive practice mode** | Clean full-screen playback with no UI distraction — taps/swipes never interrupt playback; double-tap the corner button to exit |
| 🔬 **Fine positioning** | 0.1s / 1s step buttons while paused, plus **live frame preview while scrubbing** the timeline |
| 📌 **Frame marks (bookmarks)** | Add thumbnail + custom text at any frame and jump back anytime; during immersive playback a **captioned reminder** appears at the top ±3s around a marked time (e.g. "switch fingering here") |
| 📚 **Video library** | Home screen list with thumbnails, editable notes, last progress; deletion cascades to all associated local data (source file untouched) |
| 💾 **Session state memory** | Reopening a video restores position, speed, A/B selection, countdown config and audio offset |
| 🎧 **Audio delay correction** | ±100ms in 5ms steps to fix audio/video desync caused by recording |
| 📱 **Auto orientation** | Landscape/portrait follows the video's recorded rotation, scaled to fit — never cropped or stretched |
| 🌗 **Modern Material 3** | Dark/light themes + Android 12+ dynamic color |

### Tech Stack

- **Language / UI**: Kotlin 2.2.10 · Jetpack Compose (Material 3) · Coroutines / Flow
- **Playback engine**: Media3 ExoPlayer 1.10.1 (pitch-preserving speed, Clipping + Looping sources, audio processing pipeline)
- **Architecture**: MVVM + Repository + unidirectional data flow (UI → ViewModel → player abstraction); ExoPlayer is fully encapsulated behind a player interface
- **Storage**: DataStore Preferences + SAF (persisted `content://` Uri grants — zero storage permissions)
- **Serialization**: kotlinx.serialization
- **Build**: AGP 9.3.0 · Gradle · minSdk 29 / target & compile SDK 37
- **Testing**: JUnit4 · Turbine · Mockito · Compose UI Test · instrumented smoke tests

### Project Structure

```
app/src/main/java/com/rythm/app/
├── ui/          # Compose screens: library / player / immersive mode / countdown overlay
├── viewmodel/   # PlayerViewModel / LibraryViewModel / BookmarkViewModel
├── domain/      # Pure-Kotlin rules: LoopPolicy / FrameStepPolicy / LibraryPolicy
├── data/        # Repositories: video / library / bookmarks / preferences
├── player/      # Player engine layer (sole ExoPlayer contact): manager / loop monitor / countdown / audio delay
├── model/       # State & data models
└── util/        # Time formatting helpers
```

### Build

Requirements: JDK 17+, Android SDK (compileSdk 37). Open in Android Studio and run, or use the command line:

```bash
# Debug (installable)
gradlew.bat assembleDebug          # Windows
./gradlew assembleDebug            # macOS / Linux

# Release (requires signing config in app/sign/key.properties — not committed)
gradlew.bat assembleRelease
```

Release artifacts are named `rhythmplayer-<version>.apk` (e.g. `rhythmplayer-1.0.3.apk`).

### Testing

```bash
gradlew.bat testDebugUnitTest              # unit tests (domain rules, state folding, loop/frame policies)
gradlew.bat connectedDebugAndroidTest      # instrumented smoke tests (device or emulator required)
```

### Privacy

- **Zero permissions**: no runtime permissions and no `INTERNET` permission in the manifest
- **Local-only**: videos, bookmarks and history are stored on-device (DataStore + files); nothing is uploaded anywhere
- **SAF read-only**: videos are accessed via the system file picker; your source files are never modified; uninstalling removes everything

### License

Licensed under the [MIT License](LICENSE). See the [LICENSE](LICENSE) file for details.