# IrisShot

[简体中文](README.md) | [English](README_EN.md)

IrisShot 是一款面向 Android 横屏游戏的长截图工具，通过屏幕捕获、自动滑动、画面分析与图像拼接生成长截图。

## 主要功能

- 支持横向和纵向滚动截图
- 支持配置截图区域、滑动区域和固定区域
- 支持 MediaProjection 与 Shizuku 两种捕获方式
- 支持导出 JPG 和 PNG 图片
- 支持结果预览、裁剪、重新拼接与分享

## 系统要求

- Android 10（API 29）及以上
- 自动滑动需要无障碍权限，或使用 Shizuku 模式

## 构建

使用 Android Studio 打开项目并同步 Gradle，然后选择 `app` 配置进行构建。

- JDK 21
- Android SDK 36.1
- Gradle 9.4.1
