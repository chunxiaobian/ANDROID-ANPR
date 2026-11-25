# ADB 真机性能瓶颈定位与优化记录

本文档记录在 **Honor 10（`COL-AL10` / Kirin 970，Android 10）** 真机上通过 ADB 实测定位系统与进程级性能瓶颈、实施针对性代码优化，以及优化前后的实测对比数据。

> 相关底层算力分析可参阅：[Software_Compute_Bottlenecks_Analysis.md](Software_Compute_Bottlenecks_Analysis.md)

---

## 1. 测试设备与基准运行配置

* **测试设备**：Honor 10（`COL-AL10` / Kirin 970，8 核：`4×A73 + 4×A53`，5.5 GB RAM）
* **ADB 连接**：`192.168.31.221:5555`
* **基准配置**（通过 `/info.json` 与 `/api/alpr/config` 读取）：
  * 采集与编码：`CameraX`、`1440×1080 @30fps`、视频编码 `H.265 (HEVC)`、码率 `3000 kbps`、`allowLimitedSurface = true`
  * ALPR 配置：`detLevel = 1`、`threads = 4`、`intervalMs = 200`、`useGpu = false`、`motionGateEnabled = true`、`motionCropEnabled = true`、`headlightCaptureEnabled = true`、`vehicleFilterMode = "tflite"`（`vehicle_efficientdet_lite0.tflite`，`vehicleDetectMs ≈ 140–146ms`）、`maskEnabled = true`
* **测试场景**：室内静止画面（`vehicleFilterStatus = "idle"`，`motionDebug.hasMotion = false`）

---

## 2. 优化前 ADB 实测瓶颈分析

通过 `adb shell top`、`top -H`、`logcat`、`dumpsys meminfo` 与 `/sys/devices/system/cpu/cpufreq` 采样，发现静止待机状态下整机 CPU 负载高达 **225%～307% / 800%**，小核簇长期顶在最高频 `1844 MHz`，历史峰值温度达 `62°C`（`cluster1`）。核心瓶颈如下：

### 瓶颈 1：每次 HTTP 请求重复初始化 `SecureStorage` 触发 Keymaster IPC 与 `logd` 刷屏
* **实测表现**：
  * `android.hardware.keymaster@3.0-service` 占用 **15.0%～24.1% CPU**，`keystore` 占用 **2.0%～3.4% CPU**；
  * `logcat` 在 57 ms 内连续打印数十条 `HisiKeyMaster` / `keymaster_worker`（`HisiKmBegin/Update/Finish`）日志，直接将系统 `logd` 进程拖到 **36.0%～100% CPU**（单线程 `logd.writer` 达 93.4%）；
  * 应用内协程线程 `DefaultDispatch` 占用 **10.0%～13.1% CPU**。
* **代码根因**：
  * [StreamingServerHelper.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/StreamingServerHelper.kt#L495-L514) 的 `handleClientConnection` 在每一次 HTTP 连接进入时都会直接执行 `SecureStorage(context)`，且写在读取 `enable_auth` 之前。
  * [SecureStorage.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/SecureStorage.kt#L18-L51) 每次实例化都会重建 `MasterKey` 与 `EncryptedSharedPreferences`，并通过 `AndroidKeyStore` / TEE 硬件解密 `KEY_USERNAME` 与 `KEY_PASSWORD`。

### 瓶颈 2：静止状态下仍全帧率执行 `1440×1080` `YUV_420_888 → NV21` 转换
* **实测表现**：
  * `pool-4-thread-1`（CameraX `analysisExec` 单线程）占用 **19.0%～29.4% CPU**（进程累计 CPU 时间高达 `454 分钟`）。
* **代码根因**：
  * [CameraXCapture.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/CameraXCapture.kt#L197-L214) 在每次满足 `canProcessNow()`（默认 `200ms` 即每秒 5 次）时，无条件先调用 [convertYUV420toNV21](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/ImageConversionHelper.kt#L10-L65) 将整张 `1440×1080`（`2.33 MB`）逐行重排为 `NV21`，然后才送入 `PlateRecognizer` 做运动门控判断。
  * 实际上 `MotionGate` 仅采样 `32×18 = 576` 点，`Top-Hat` 仅采样 `160×90 = 14,400` 点（不足整帧像素的 1%），静止退避时整帧 `NV21` 转换完全是冗余内存搬运。

### 瓶颈 3：静止心跳（`STATIC_HEARTBEAT_INTERVAL`）过于频繁唤醒重型 AI 推理
* **实测表现**：
  * `pool-2-thread-1`（`inferExecutor` 线程）占用 **6.3%～14.0% CPU**（累计 `98 分钟`），单次 `EfficientDet-Lite0` 推理耗时 `146ms`。
* **代码根因**：
  * [PlateRecognizer.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L355) 原设定 `STATIC_HEARTBEAT_INTERVAL = 8` 且退避间隔 `alprBackoffInterval = max(intervalMs, motionBackoffMs)`（默认仅 `1000ms`），导致画面完全静止时每 `1.6s` 仍强制唤醒一次 TFLite + HyperLPR3。

### 瓶颈 4：Camera HAL 伴随人脸检测与 30fps ISP 开销
* **实测表现**：
  * `CameraDaemon kirin970` 占用 **44.8%～57.0% CPU**，日志可见 `FNFaceDetect`、`IMX498_PD`、`IspEnhncAlgo` 每帧持续运行，其中 `FNFaceDetect` 为车牌抓拍完全不需要的统计模块。

---

## 3. 已实施的代码优化

### 3.1 `SecureStorage` 进程级单例 + 内存键值缓存 + 认证前置判断
* **[SecureStorage.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/SecureStorage.kt#L20-L143)**：
  * 将 `EncryptedSharedPreferences` 提升为 `companion object` 进程级单例（基于 `context.applicationContext` 与双重检查锁 `initializationLock` 初始化一次）；
  * 增加 `ConcurrentHashMap` 键值缓存 `valueCache`（使用哨兵常量 `MISSING_SENTINEL` 缓存未配置键，防止 `null`/空键穿透到 Keystore），并在 `putSecureString` / `removeSecureString` 时同步更新缓存。
* **[StreamingServerHelper.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/StreamingServerHelper.kt#L495-L512)**：
  * 将 `enable_auth` 读取移到 `SecureStorage(context)` 之前，关闭认证时彻底跳过安全存储访问。

### 3.2 零拷贝 `ImageProxy` 平面直接采样（按需延迟 `NV21` 转换）
* **[PlateRecognizer.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L948-L1430)**：
  * 将 `detectMovingGlareTopHat` 与 `analyzeSceneMotion` 拆分为 **采样阶段**（NV21 与 `ImageProxy` 双路径）和 **公共计算阶段**（`finishDetectMovingGlareTopHat`、`finishAnalyzeSceneMotion`）。
  * 新增 `sampleLumaGridFromImageProxy` 与 `sampleGlareGridFromImageProxy`：直接结合 `image.cropRect`、`rowStride`、`pixelStride` 以**绝对下标**读取 `ImageProxy.planes` 原始 `ByteBuffer`（严格不改动 `position()`/`limit()`，避免干扰后续编码器与 `MotionMonitor`）。
  * 新增 `processImageProxyAsync(image, rotationDegrees, convertToNv21, onDetected): Boolean` 与公共流水线 `runPipeline` / `finishProcessAfterMotion`：静止退避或启发式预检拦截时直接返回 `false`，**完全跳过整帧 `YUV→NV21` 转换**；仅当真正需要送入 TFLite/HyperLPR3 推理时才调用 `convertToNv21()`。
* **[CameraXCapture.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/CameraXCapture.kt#L200-L227)**：
  * `ImageAnalysis` 改用 `plateRecognizer.processImageProxyAsync`；静止跳过转换时，仅每 `4.5s` 低频刷新一次 `latestNv21`（满足 `FRAME_FALLBACK_MAX_AGE_MS = 6_000L` 的抓拍兜底保鲜要求，拷贝频率由每秒 5 次降为每 4.5 秒 1 次）。

### 3.3 拉长静止 AI 心跳间隔
* **[PlateRecognizer.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L356)** & **[第 2099–2108 行](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L2099-L2108)**：
  * 将 `STATIC_HEARTBEAT_INTERVAL` 从 `8` 调整为 `50`，并将静止心跳时间门槛提升至 `maxOf(currentMotionBackoffMs * 10L, 10_000L)`（由每 ~1.6 秒唤醒 1 次降为 ≥10 秒 1 次）。

### 3.4 显式关闭 CameraX 分析流人脸检测统计
* **[CameraXCapture.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/CameraXCapture.kt#L174-L177)**：
  * 通过 `Camera2Interop.Extender` 设置 `CaptureRequest.STATISTICS_FACE_DETECT_MODE = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF`。

---

## 4. 优化前后 ADB 真机实测对比（同一设备 `192.168.31.221:5555`，静止 `idle` 态）

| 观测指标 | 优化前 | 优化后 | 变化幅度 / 说明 |
| :--- | :--- | :--- | :--- |
| **`keymaster@3.0-service` CPU** | `15.0% ~ 24.1%` | **0%（未上榜）** | **彻底消除**（原每秒数十次 TEE 解密归零） |
| **`keystore` CPU** | `2.0% ~ 3.4%` | **0%（未上榜）** | **彻底消除** |
| **`logd`（`logd.writer`）CPU** | `36.0% ~ 100%`（线程 93.4%） | **`0% ~ 4.0%`** | **下降约 90%～96%**，Keymaster 刷屏日志完全消失 |
| **App `pool-2-thread-1`（AI 推理线程）CPU** | `6.3% ~ 14.0%` | **< 1.0%（退出前 35 线程榜）** | 静止心跳从 `~1.6s` 降至 `≥10s`，AI 待机近乎零唤醒 |
| **App `DefaultDispatch` CPU** | `9.0% ~ 13.1%` | `9.2% ~ 10.0%`（峰值 19%→10%） | 协程不再阻塞在 Keystore IPC 上 |
| **App 进程总 CPU** | `51.0% ~ 68.9%` | `46.6% ~ 52.0%` | **峰值下降约 17%～22%** |
| **整机系统空闲（idle / 800%）** | `493% ~ 575%` | **`582% ~ 595%`** | 整机系统负载明显降低 |
| **小核簇频率 (`cpu0`)** | `1844 MHz`（满频） | **`509 MHz`** | **下降 72.4%**（不再长期顶频） |
| **大核簇频率 (`cpu4`)** | `1863 ~ 2362 MHz` | **`1652 MHz`** | 峰值负载下降 |
| **App 内存占用 (`TOTAL PSS` / `SwapPss`)** | `212.2 MB` / `40.7 MB` | **`172.8 MB` / `0.12 MB`** | 内存减少 ~39 MB，Swap 压力大幅缓解 |
| **功能与稳定性验证** | — | `/api/plate`、`/info.json` 正常 200；`ALPR_GLARE` 与 `idle` 正常 | 无 `IndexOutOfBoundsException` 或崩溃 |

---

## 5. 剩余开销与后续可继续调优方向

优化后剩余的主要 CPU 消耗来自相机 30fps 视频管线与高频帧回调（非 Bug，属于采集频率与模型选择权衡）：

1. **采集帧率 `30fps → 15/20fps`**（对 `CameraDaemon` 50%～57% 与 `SensorService` 15%～28% 最有效）：车牌抓拍监控场景将 `stream_fps` 降至 `15~20fps` 可按比例降低 HAL 流水线和 `analysisExec` 频率。
2. **进一步降低 `pool-4-thread-1`（当前 23%）**：
   * 在静止 `idle` 态下，[PlateRecognizer.kt](file:///home/chunxiaobian/Projects/ANDROID-ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L1943) 默认每 `200ms` 运行一次 `160×90`（14,400 点）9×9 形态学开运算与 4 趟全循环；若将 `intervalMs` 调至 `300~400ms` 或在非夜间时段关闭 `headlightCaptureEnabled`，可继续明显降低待机 CPU。
   * 若需要进一步压低 `MotionMonitor.processFrame(img)` 30fps 遍历频率，可在未开启 ONVIF 移动告警且已启用 `PlateRecognizer` 运动门控时做节流。
3. **推理耗时与模型替换**：当前 `EfficientDet-Lite0`（`threads = 4`）单次耗时 `140~146ms`，对固定低速出入口可切换为 `MobileNetV1-SSD` 并将 `threads` 设为 `2` 以降低单次峰值发热。
