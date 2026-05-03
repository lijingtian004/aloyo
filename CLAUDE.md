# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

ALOYO 是一个 Android 实时 YOLO 目标检测应用。通过 MediaProjection 捕获屏幕画面，使用 NCNN 推理引擎（Vulkan GPU 加速）运行 YOLO 模型，将检测结果以悬浮窗 overlay 形式显示在屏幕上。

- **语言**: Kotlin（主体）+ C++（JNI 层）
- **UI**: 传统 Android View + ViewBinding（非 Jetpack Compose）
- **构建**: Gradle 8.5 Kotlin DSL，AGP 8.2.2，Kotlin 1.9.22
- **最低 SDK**: 26 (Android 8.0)，目标 SDK: 34

## 构建命令

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (requires signing keystore)
./gradlew assembleRelease

# Lint 检查
./gradlew lint
```

**构建前置条件**: `core-inference` 模块需要 NCNN Android Vulkan SDK（版本 20260113）放置在 `core-inference/libs/ncnn-android-vulkan/`。缺少此 SDK 时 CMake 原生构建会跳过，推理功能无法运行。

**NDK 要求**: 27.0.12077973，用于编译 JNI 桥接层。

## 模块架构

项目采用多模块 + 接口隔离的架构：

```
app（组装层）→ common（接口定义）← core-*（各自实现）
```

- **`common`** — 所有跨模块接口（`IInferenceEngine`, `ICaptureSource`, `IOverlayRenderer`, `ILogger`, `IModelManager`）和共享数据类
- **`core-inference`** — NCNN 推理引擎，JNI 桥接 C++/Kotlin，支持 YOLOv5/v7/v8 自动检测
- **`core-capture`** — MediaProjection 屏幕捕获 + 前台 Service
- **`core-overlay`** — 悬浮窗 overlay，绘制检测框
- **`core-model`** — 模型文件管理（assets / 外部存储）
- **`core-logger`** — 文件日志系统（带 rotation）
- **`app`** — 组装层：`InferencePipeline.kt` 连接 capture → inference → overlay 数据流

**关键约束**: `core-*` 模块之间零依赖，所有通信通过 `common` 接口。

## 数据流

```
MediaProjection 屏幕捕获
  → Bitmap frame callback
  → YoloPreProcessor（resize、归一化、BGR 转换）
  → NcnnInferenceEngine（JNI → NCNN C++ → Vulkan GPU）
  → YoloDecoder + YoloPostProcessor（解码输出 + NMS）
  → OverlayManager → DetectionOverlayView（悬浮窗绘制）
```

由 `InferencePipeline.kt` 编排整个流程。

## JNI 桥接层

- **C++ 层**: `core-inference/src/main/jni/aloyo_inference_jni.cpp` — 直接调用 NCNN API，模型加载、推理执行、输出提取
- **Kotlin 层**: `NcnnInferenceEngine.kt` — 封装 JNI 调用，处理前后处理，实现 `IInferenceEngine` 接口
- NCNN **静态链接**到单一 `libaloyo_inference.so`（含 glslang/Vulkan 着色器编译库）

## 已知设计决策

- Vulkan GPU 加速默认开启
- `ModelConfig.validate()` 修复 Gson 反序列化丢失 Kotlin 默认值的问题
- 自适应输入尺寸：当捕获帧小于模型配置输入尺寸时，使用实际帧尺寸避免有损放大
- YOLO 多版本支持：`UnifiedYoloDecoder` 自动检测输出格式（v5/v7/v8）

## CI/CD

GitHub Actions（`.github/workflows/android-ci.yml`）：
- 推送到 `main`/`develop` 或 PR 到 `main` 时触发
- JDK 17 (Temurin)，自动下载 NCNN SDK 和安装 NDK
- 仅构建 debug APK 并上传 artifact

## 测试

当前项目无测试目录（`src/test/`、`src/androidTest/` 均不存在）。

## 权限要求

应用运行时需要：
- `SYSTEM_ALERT_WINDOW` — 悬浮窗权限（引导用户到设置页开启）
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — 屏幕捕获前台服务
