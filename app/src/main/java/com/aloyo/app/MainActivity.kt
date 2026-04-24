package com.aloyo.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aloyo.capture.ScreenCaptureService
import com.aloyo.common.CaptureRegion
import com.aloyo.common.Detection
import com.aloyo.common.IInferenceEngine
import com.aloyo.common.ILogger
import com.aloyo.common.ModelConfig
import com.aloyo.common.PerformanceMetrics
import com.aloyo.inference.NcnnInferenceEngine
import com.aloyo.model.ModelManager
import com.aloyo.overlay.OverlayManager

/**
 * 主界面Activity
 * 负责权限请求、模型选择、截屏控制等用户交互
 * 连接截屏采集→模型推理→悬浮窗显示的完整流水线
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
    }

    // 核心组件
    private lateinit var logger: ILogger
    private lateinit var inferenceEngine: IInferenceEngine
    private lateinit var modelManager: ModelManager
    private lateinit var overlayManager: OverlayManager

    // UI组件
    private lateinit var modelSpinner: Spinner
    private lateinit var btnStartCapture: Button
    private lateinit var btnStopCapture: Button
    private lateinit var btnShowOverlay: Button
    private lateinit var btnHideOverlay: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvMetrics: TextView

    // 运行状态
    private var isCapturing = false
    private var isInferenceReady = false

    // MediaProjection授权数据
    private var projectionResultCode: Int = -1
    private var projectionResultData: Intent? = null

    // 性能指标统计
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L
    private var currentFps = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化核心组件
        val app = application as ALoyoApp
        logger = app.logger
        inferenceEngine = NcnnInferenceEngine()
        modelManager = ModelManager(this)
        overlayManager = OverlayManager(this)

        // 初始化UI
        initViews()
        initModelSpinner()
        initButtons()

        logger.info(TAG, "MainActivity created")
    }

    /**
     * 初始化视图组件
     */
    private fun initViews() {
        modelSpinner = findViewById(R.id.spinner_model)
        btnStartCapture = findViewById(R.id.btn_start_capture)
        btnStopCapture = findViewById(R.id.btn_stop_capture)
        btnShowOverlay = findViewById(R.id.btn_show_overlay)
        btnHideOverlay = findViewById(R.id.btn_hide_overlay)
        tvStatus = findViewById(R.id.tv_status)
        tvMetrics = findViewById(R.id.tv_metrics)

        updateStatus("就绪")
    }

    /**
     * 初始化模型选择下拉框
     */
    private fun initModelSpinner() {
        val models = modelManager.availableModels
        if (models.isEmpty()) {
            updateStatus("未找到可用模型，请在assets/models目录中放置模型文件")
            return
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter
    }

    /**
     * 初始化按钮事件
     */
    private fun initButtons() {
        // 开始截屏推理（自动加载模型）
        btnStartCapture.setOnClickListener {
            if (!isInferenceReady) {
                if (!loadSelectedModel()) {
                    return@setOnClickListener
                }
            }
            startCaptureFlow()
        }

        // 停止截屏推理
        btnStopCapture.setOnClickListener {
            stopCapture()
        }

        // 显示悬浮窗
        btnShowOverlay.setOnClickListener {
            if (!overlayManager.hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            overlayManager.show()
        }

        // 隐藏悬浮窗
        btnHideOverlay.setOnClickListener {
            overlayManager.hide()
        }
    }

    /**
     * 加载选中的模型
     */
    private fun loadSelectedModel(): Boolean {
        val modelName = modelSpinner.selectedItem?.toString() ?: run {
            Toast.makeText(this, "请选择模型", Toast.LENGTH_SHORT).show()
            return false
        }

        updateStatus("正在加载模型: $modelName")
        logger.info(TAG, "Loading model: $modelName")

        // 先释放之前的模型
        if (isInferenceReady) {
            inferenceEngine.release()
            isInferenceReady = false
        }

        // 加载模型
        val loaded = modelManager.loadModel(modelName)
        if (!loaded) {
            updateStatus("模型加载失败")
            logger.error(TAG, "Failed to load model: $modelName")
            Toast.makeText(this, "模型加载失败", Toast.LENGTH_SHORT).show()
            return false
        }

        // 初始化推理引擎
        val paths = modelManager.getCurrentModelPaths()
        val config = modelManager.getModelConfig(modelName)
        if (paths == null || config == null) {
            updateStatus("模型路径或配置无效")
            logger.error(TAG, "Invalid model paths or config")
            return false
        }

        val initialized = inferenceEngine.initialize(paths.paramPath, paths.binPath, config)
        if (!initialized) {
            updateStatus("推理引擎初始化失败")
            logger.error(TAG, "Failed to initialize inference engine")
            return false
        }

        isInferenceReady = true
        updateStatus("模型已加载: $modelName")
        logger.info(TAG, "Model loaded successfully: $modelName")
        return true
    }

    /**
     * 启动截屏推理流程
     */
    private fun startCaptureFlow() {
        // 检查悬浮窗权限
        if (!overlayManager.hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }

        // 请求MediaProjection授权
        requestMediaProjection()
    }

    /**
     * 请求MediaProjection授权
     */
    private fun requestMediaProjection() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    /**
     * 开始截屏和推理
     */
    private fun startCapture(resultCode: Int, data: Intent) {
        // 启动截屏服务
        ScreenCaptureService.start(this, resultCode, data)

        // 设置截屏帧回调
        ScreenCaptureService // 通过Service获取CaptureManager
        // 注意：实际实现中需要通过Service连接获取CaptureManager

        // 显示悬浮窗
        overlayManager.show()

        isCapturing = true
        updateStatus("截屏推理运行中")
        logger.info(TAG, "Capture and inference started")
    }

    /**
     * 停止截屏
     */
    private fun stopCapture() {
        ScreenCaptureService.stop(this)
        overlayManager.hide()
        isCapturing = false
        updateStatus("已停止")
        logger.info(TAG, "Capture and inference stopped")
    }

    /**
     * 处理截屏帧回调
     * 将截屏帧送入推理引擎，然后将结果显示在悬浮窗上
     */
    private fun onCaptureFrame(bitmap: Bitmap, captureTimeMs: Long) {
        if (!isInferenceReady) return

        try {
            // 执行推理
            val (detections, metrics) = inferenceEngine.inferWithMetrics(bitmap)

            // 更新悬浮窗
            overlayManager.updateDetections(detections, metrics.copy(captureLatencyMs = captureTimeMs))

            // 更新UI上的性能指标
            updateMetricsUI(metrics)

            // 回收Bitmap
            bitmap.recycle()
        } catch (e: Exception) {
            logger.error(TAG, "Error processing frame", e)
        }
    }

    /**
     * 更新性能指标UI
     */
    private fun updateMetricsUI(metrics: PerformanceMetrics) {
        frameCount++
        val currentTime = System.currentTimeMillis()

        // 每秒更新一次FPS
        if (currentTime - lastFpsUpdateTime >= 1000) {
            currentFps = frameCount * 1000f / (currentTime - lastFpsUpdateTime)
            frameCount = 0
            lastFpsUpdateTime = currentTime
        }

        runOnUiThread {
            tvMetrics.text = "FPS: ${"%.1f".format(currentFps)} | 延迟: ${metrics.inferenceLatencyMs}ms"
        }
    }

    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    projectionResultCode = resultCode
                    projectionResultData = data
                    startCapture(resultCode, data)
                    logger.info(TAG, "MediaProjection permission granted")
                } else {
                    Toast.makeText(this, "需要截屏权限才能运行", Toast.LENGTH_SHORT).show()
                    logger.warn(TAG, "MediaProjection permission denied")
                }
            }
            REQUEST_OVERLAY_PERMISSION -> {
                if (overlayManager.hasOverlayPermission()) {
                    overlayManager.show()
                    logger.info(TAG, "Overlay permission granted")
                } else {
                    Toast.makeText(this, "需要悬浮窗权限才能显示检测结果", Toast.LENGTH_SHORT).show()
                    logger.warn(TAG, "Overlay permission denied")
                }
            }
        }
    }

    /**
     * 更新状态文本
     */
    private fun updateStatus(status: String) {
        runOnUiThread {
            tvStatus.text = "状态: $status"
        }
    }

    override fun onDestroy() {
        stopCapture()
        inferenceEngine.release()
        overlayManager.release()
        logger.info(TAG, "MainActivity destroyed")
        super.onDestroy()
    }
}
