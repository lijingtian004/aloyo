package com.aloyo.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
 * 负责权限请求、模型选择与导入、截屏控制等用户交互
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
    private lateinit var inferencePipeline: InferencePipeline

    // UI组件
    private lateinit var modelSpinner: Spinner
    private lateinit var btnImportModel: Button
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

    // 截屏服务绑定相关
    private var captureService: ScreenCaptureService? = null
    private var isServiceBound = false

    // 服务连接回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenCaptureService.CaptureServiceBinder
            captureService = binder.getService()
            isServiceBound = true

            // 服务连接后，设置帧回调到推理流水线
            captureService?.setFrameCallback(object : com.aloyo.common.ICaptureSource.FrameCallback {
                override fun onFrame(bitmap: Bitmap, captureTimeMs: Long) {
                    onCaptureFrame(bitmap, captureTimeMs)
                }

                override fun onError(error: String) {
                    logger.error(TAG, "Capture error: $error")
                }
            })

            // 启动推理流水线
            inferencePipeline.start()

            logger.info(TAG, "CaptureService bound and callback set")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isServiceBound = false
            inferencePipeline.stop()
            logger.info(TAG, "CaptureService unbound")
        }
    }

    // SAF文件选择器：依次选择param、bin、config文件
    private var importParamUri: Uri? = null
    private var importBinUri: Uri? = null
    private var importConfigUri: Uri? = null

    // 选择param文件的launcher
    private val pickParamLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            importParamUri = it
            updateStatus("已选择param文件，请选择bin文件")
            // 继续选择bin文件
            pickBinLauncher.launch(arrayOf("*/*"))
        }
    }

    // 选择bin文件的launcher
    private val pickBinLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            importBinUri = it
            updateStatus("已选择bin文件，请选择config.json文件")
            // 继续选择config文件
            pickConfigLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    // 选择config文件的launcher
    private val pickConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            importConfigUri = it
            // 三个文件都选完了，弹出命名对话框
            showModelNameDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化核心组件
        val app = application as ALoyoApp
        logger = app.logger
        inferenceEngine = NcnnInferenceEngine()
        modelManager = ModelManager(this)
        overlayManager = OverlayManager(this)
        inferencePipeline = InferencePipeline(this, inferenceEngine, overlayManager)

        // 悬浮窗暂停回调：暂停/继续推理流水线
        overlayManager.onPauseToggle = { paused ->
            if (paused) {
                inferencePipeline.stop()
                updateStatus("推理已暂停")
            } else {
                inferencePipeline.start()
                updateStatus("截屏推理运行中")
            }
        }

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
        btnImportModel = findViewById(R.id.btn_import_model)
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
        refreshModelSpinner()
    }

    /**
     * 刷新模型下拉框数据
     */
    private fun refreshModelSpinner() {
        val models = modelManager.availableModels
        if (models.isEmpty()) {
            updateStatus("未找到可用模型，请点击「导入模型」添加模型文件")
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
        // 导入模型按钮
        btnImportModel.setOnClickListener {
            startImportModelFlow()
        }

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
            updateStatus("悬浮窗已显示（等待截屏推理数据）")
        }

        // 隐藏悬浮窗
        btnHideOverlay.setOnClickListener {
            overlayManager.hide()
        }
    }

    /**
     * 开始导入模型流程
     * 使用SAF文件选择器依次选择param、bin、config三个文件
     */
    private fun startImportModelFlow() {
        importParamUri = null
        importBinUri = null
        importConfigUri = null
        updateStatus("请选择模型param文件")
        // 先选择param文件
        pickParamLauncher.launch(arrayOf("*/*"))
    }

    /**
     * 显示模型命名对话框
     * 用户输入模型名称后执行导入
     */
    private fun showModelNameDialog() {
        val input = EditText(this).apply {
            hint = "例如: yolov8n_custom"
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("为模型命名")
            .setMessage("请输入导入模型的名称（仅用于显示，不能与已有模型重名）")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val modelName = input.text.toString().trim()
                if (modelName.isEmpty()) {
                    Toast.makeText(this, "模型名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (modelManager.availableModels.contains(modelName)) {
                    Toast.makeText(this, "模型名称已存在，请换一个", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performImport(modelName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行模型导入
     * 将SAF选择的文件复制到应用私有目录
     */
    private fun performImport(modelName: String) {
        val paramUri = importParamUri
        val binUri = importBinUri
        val configUri = importConfigUri

        if (paramUri == null || binUri == null || configUri == null) {
            Toast.makeText(this, "文件选择不完整，请重新导入", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus("正在导入模型: $modelName ...")

        try {
            // 将SAF Uri的文件内容复制到应用私有目录
            val modelDir = java.io.File(filesDir, "imported_models/$modelName")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            // 复制param文件
            val paramFile = java.io.File(modelDir, "model.param")
            contentResolver.openInputStream(paramUri)?.use { input ->
                paramFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 复制bin文件
            val binFile = java.io.File(modelDir, "model.bin")
            contentResolver.openInputStream(binUri)?.use { input ->
                binFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 复制config文件
            val configFile = java.io.File(modelDir, "config.json")
            contentResolver.openInputStream(configUri)?.use { input ->
                configFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 刷新模型列表
            modelManager.refreshModelList()
            refreshModelSpinner()

            updateStatus("模型导入成功: $modelName")
            Toast.makeText(this, "模型「$modelName」导入成功！", Toast.LENGTH_SHORT).show()
            logger.info(TAG, "Model imported successfully: $modelName")

            // 自动选择刚导入的模型
            val models = modelManager.availableModels
            val index = models.indexOf(modelName)
            if (index >= 0) {
                modelSpinner.setSelection(index)
            }
        } catch (e: Exception) {
            updateStatus("模型导入失败")
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            logger.error(TAG, "Error importing model: $modelName", e)
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
     * 启动截屏服务并绑定，连接推理流水线
     */
    private fun startCapture(resultCode: Int, data: Intent) {
        // 启动截屏服务
        ScreenCaptureService.start(this, resultCode, data)

        // 绑定截屏服务以获取帧回调
        val bindIntent = Intent(this, ScreenCaptureService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

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
        // 停止推理流水线
        inferencePipeline.stop()

        // 解绑截屏服务
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        // 停止截屏服务
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
