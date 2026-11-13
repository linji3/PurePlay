# PurePlay

An ultra-minimalist, ad-free, zero-network-permission Android local video player built with Kotlin & AndroidX Media3 (ExoPlayer).

一款极简、无广告、无联网权限的安卓本地视频播放器，基于 Kotlin 与 AndroidX Media3 (ExoPlayer) 构建，专为替代臃肿、广告繁多的手机自带播放器而打造。

---

## Download / 下载安装

Go to [Releases](../../releases) to download the latest `PurePlay-v1.0.0.apk` (Android 8.0+).

前往 [Releases](../../releases) 页面下载最新版 `PurePlay-v1.0.0.apk`（支持 Android 8.0 及以上系统）。

---

## Features / 核心特性

- **Zero Ads & Zero Network Permission / 零广告、零联网权限**
  No `INTERNET` permission in `AndroidManifest.xml` — 100% offline, no ads, no tracking, no background bloat.
  `AndroidManifest.xml` 中未申请任何网络权限，纯本地离线运行，无广告、无推送、无后台唤醒。

- **Broad Format Support / 主流视频格式支持**
  Powered by Media3 (ExoPlayer) with hardware/software decoder fallback, supporting MP4, MKV, WebM, MOV, FLV, TS, AVI, and more.
  基于 Media3 (ExoPlayer) 并启用硬/软解回退，支持 MP4、MKV、WebM、MOV、FLV、TS、AVI 等主流视频格式。

- **Butter-Smooth Pinch-to-Zoom & Pan / 丝滑双指缩放与拖动**
  Rendered on `TextureView` for 60/120fps fluid `0.75x ~ 6.0x` pinch-to-zoom around the focal point, single/two-finger pan, elastic snap-back below `1.0x`, and one-tap reset.
  采用 `TextureView` 渲染，支持 `0.75x ~ 6.0x` 跟手双指自由缩放与拖动、小于 `1.0x` 松手自动回弹、顶部一键重置缩放。

- **Practical Playback Tools / 实用播放辅助**
  Playback speed (`0.5x ~ 2.0x`), aspect ratio toggle (`适应 Fit / 填充 Crop / 拉伸 Fill`), screen rotation, touch lock, and automatic playback position memory.
  支持倍速调节（`0.5x ~ 2.0x`）、画面比例切换（适应 / 填充 / 拉伸）、横竖屏切换、锁屏防误触、自动记忆上次播放进度。

- **System Integration / 无缝系统调用**
  Open videos directly from the app or via **"Open with PurePlay"** from your phone's File Manager or Gallery.
  支持桌面打开选片或扫描本地视频，也支持在系统文件管理 / 相册中点击视频「用其他应用打开 → PurePlay」直接播放。

---

## Gesture Controls / 手势操作指南

| Gesture / 手势 | Action (EN) | 功能说明 (中文) |
| --- | --- | --- |
| **Pinch (2 fingers)** | Free zoom (`0.75x ~ 6.0x`) & pan | 双指自由放大 / 缩小画面并拖动 |
| **Double-tap Left** | Rewind 10s (stackable on repeated taps) | 双击左侧倒退 10 秒（连续双击可累加） |
| **Double-tap Right** | Fast-forward 10s (stackable on repeated taps) | 双击右侧快进 10 秒（连续双击可累加） |
| **Double-tap Center** | Play / Pause | 双击屏幕中央播放 / 暂停 |
| **Single-tap** | Show / Hide controls | 单击屏幕显示 / 隐藏控制栏 |
| **Long-press** | Hold for `2.0x` speed, release to restore | 长按屏幕 2.0x 倍速播放，松手恢复 |
| **Horizontal Swipe** | Scrub / Seek preview | 左右滑动调节播放进度 |
| **Vertical Swipe (Left / Right)** | Screen Brightness / Media Volume | 上下滑动（左半屏调亮度 / 右半屏调音量） |

---

## Build from Source / 源码构建

Requirements / 环境要求：JDK 17, Android SDK 34, Gradle 8.7+

```bash
gradle assembleDebug
```

The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

编译完成后安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

---

## License / 开源协议

[MIT License](LICENSE)
