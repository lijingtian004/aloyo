# ALOYO - Android YOLO Model Inference Application Design

## Overview

ALOYO is an Android application for real-time YOLO model inference using screen capture as input source. It displays detection results as an overlay on screen, supports multiple YOLO model versions, and provides comprehensive logging and performance monitoring.

## Key Decisions

- **Language**: Kotlin + Java (traditional View system)
- **Architecture**: Multi-module Gradle project
- **Inference Engine**: NCNN (primary)
- **Input Source**: Real-time screen capture via MediaProjection API
- **Display**: Floating overlay window (SYSTEM_ALERT_WINDOW permission)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Model Source**: Built-in default + external file loading

## Architecture

### Module Structure

```
aloyo/
├── app/                    → Main application module (assembly)
├── core-inference/         → Inference engine module (NCNN wrapper)
├── core-capture/           → Screen capture module (MediaProjection)
├── core-overlay/           → Floating overlay display module
├── core-model/             → Model management module
├── core-logger/            → Logging system module
└── common/                 → Shared utilities and interface definitions
```

### Module Dependencies

```
app → core-inference, core-capture, core-overlay, core-model, core-logger, common
core-inference → common
core-capture → common
core-overlay → common
core-model → common
core-logger → common
```

No cross-dependencies between core-* modules. All communication through interfaces defined in `common`.

## Module Design

### 1. common Module

Shared interfaces and utilities used across all modules.

**Key Interfaces:**
- `IInferenceEngine` - Unified inference engine interface
- `IModel` - Model abstraction interface
- `ICaptureSource` - Capture source interface
- `IDetectionResult` - Detection result interface
- `ILogger` - Logging interface
- `IOverlayRenderer` - Overlay rendering interface

**Data Classes:**
- `Detection` - Single detection result (bbox, label, confidence)
- `ModelConfig` - Model configuration (input size, labels, thresholds)
- `PerformanceMetrics` - Inference metrics (latency, FPS)

### 2. core-inference Module

NCNN inference engine wrapper with multi-version YOLO support.

**Components:**
- `NcnnInferenceEngine` - Main engine implementing `IInferenceEngine`
- `YoloPreProcessor` - Image preprocessing (resize, normalize, color conversion)
- `YoloPostProcessor` - Output decoding with NMS (per YOLO version)
- `YoloV5Decoder` / `YoloV7Decoder` / `YoloV8Decoder` - Version-specific decoders

**JNI Layer:**
- `ncnn-wrapper` - C++ JNI bridge to NCNN library
- Loads libncnn.so + model param/bin files
- Supports GPU (Vulkan) and CPU inference

**Data Flow:**
1. Bitmap → YoloPreProcessor → FloatBuffer
2. FloatBuffer → JNI NcnnInferenceEngine.run() → raw output arrays
3. Raw output → YoloPostProcessor (version-specific decoder + NMS) → List<Detection>

### 3. core-capture Module

Screen capture using MediaProjection API.

**Components:**
- `ScreenCaptureService` - Foreground service managing capture lifecycle
- `CaptureManager` - Manages MediaProjection session
- `VirtualDisplayHelper` - Creates and manages VirtualDisplay
- `ImageReaderHelper` - Processes ImageReader frames
- `CaptureRegion` - Defines capture area (full screen or custom region)

**Capture Flow:**
1. User grants MediaProjection permission
2. ScreenCaptureService starts as foreground service
3. CaptureManager creates VirtualDisplay with ImageReader
4. Each frame → Bitmap → callback to inference pipeline
5. Target: frame capture latency < 300ms

**Custom Region Support:**
- Preset ratios: 16:9, 4:3, 1:1, full screen
- Custom drag-to-select region on preview
- Region coordinates stored as CaptureRegion(x, y, width, height)

### 4. core-overlay Module

Floating window overlay for detection result display.

**Components:**
- `OverlayManager` - Manages overlay window lifecycle and permissions
- `DetectionOverlayView` - Custom View drawing detection boxes
- `OverlayConfig` - Configurable display options (box color, font size, etc.)

**Features:**
- Draws bounding boxes with labels and confidence scores
- Supports drag to reposition overlay
- Supports resize overlay
- Semi-transparent background option
- Toggle overlay visibility

### 5. core-model Module

Model file management and version switching.

**Components:**
- `ModelManager` - Central model management
- `ModelLoader` - Loads model from assets or external storage
- `ModelConfigParser` - Parses model configuration files
- `BuiltInModelProvider` - Provides built-in model files

**Model Directory Structure:**
```
models/
├── yolov5/
│   ├── model.param
│   ├── model.bin
│   └── config.json
├── yolov7/
│   ├── model.param
│   ├── model.bin
│   └── config.json
└── yolov8/
    ├── model.param
    ├── model.bin
    └── config.json
```

**Config.json Format:**
```json
{
  "version": "yolov8",
  "inputWidth": 640,
  "inputHeight": 640,
  "numClasses": 80,
  "confidenceThreshold": 0.5,
  "nmsThreshold": 0.4,
  "labels": ["person", "bicycle", ...]
}
```

### 6. core-logger Module

Structured logging system with local storage and export.

**Components:**
- `ALoyoLogger` - Main logger implementing `ILogger`
- `LogEntry` - Log entry data class (timestamp, level, tag, message)
- `LogFileWriter` - Writes logs to local files with rotation
- `LogExporter` - Exports logs as ZIP for sharing

**Log Levels:** DEBUG, INFO, WARN, ERROR

**Log Events:**
- App startup/shutdown
- Model loading/unloading
- Inference start/stop/errors
- Capture start/stop/errors
- User operations (model switch, region change, etc.)
- Performance anomalies

**Storage:**
- Log files stored in app-specific external storage
- Daily rotation, max 7 days retention
- Max single file size: 5MB
- Export as ZIP containing all log files

## Phased Development Plan

### Phase 1: Core Pipeline (Priority: Critical)
- Project scaffolding with multi-module Gradle setup
- NCNN SDK integration and JNI bridge
- core-inference: Basic YOLOv8 inference
- core-capture: MediaProjection screen capture
- core-overlay: Floating window with detection boxes
- app: Main activity connecting all modules
- **Goal**: End-to-end pipeline running - capture → infer → display

### Phase 2: Multi-Model & Metrics (Priority: High)
- core-model: Model management with version switching
- YOLOv5/v7 decoders in core-inference
- Performance metrics display (latency, FPS)
- Model configuration UI
- **Goal**: Switch between YOLO versions, see real-time metrics

### Phase 3: Logging & Security (Priority: Medium)
- core-logger: Complete logging system
- Log export functionality
- Model encryption/decryption support
- Custom capture region selection
- **Goal**: Full observability and model security

### Phase 4: CI/CD & Polish (Priority: Standard)
- GitHub Actions CI/CD pipeline
- APK signing and multi-channel packaging
- UI polish and theme support
- Memory optimization and battery management
- Edge case handling and crash recovery
- **Goal**: Production-ready distribution

## Permissions Required

- `SYSTEM_ALERT_WINDOW` - Floating overlay window
- `FOREGROUND_SERVICE` - Screen capture foreground service
- `RECORD_AUDIO` - MediaProjection requirement (Android 10+)
- `WRITE_EXTERNAL_STORAGE` - Log and model export (legacy)
- `READ_EXTERNAL_STORAGE` - External model loading (legacy)

## Error Handling Strategy

- NCNN load failure → Show error dialog, offer model re-download
- MediaProjection revocation → Stop capture, notify user, offer restart
- Overlay permission denied → Guide user to settings
- Inference timeout → Skip frame, log warning, continue
- Low memory → Reduce capture resolution, log warning

## Performance Targets

- Screen capture latency: < 300ms
- Single frame inference (YOLOv8n, 640x640): < 50ms on mid-range device
- FPS display update rate: >= 1Hz
- Overlay rendering: < 16ms (60fps)
- Memory usage: < 200MB for inference pipeline
