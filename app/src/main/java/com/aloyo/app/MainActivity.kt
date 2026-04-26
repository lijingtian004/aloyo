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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aloyo.capture.ScreenCaptureService
import com.aloyo.common.CaptureRegion
import com.aloyo.common.IInferenceEngine
import com.aloyo.common.ILogger
import com.aloyo.common.PerformanceMetrics
import com.aloyo.inference.NcnnInferenceEngine
import com.aloyo.model.ModelManager
import com.aloyo.overlay.OverlayManager
import java.io.File
import java.util.zip.ZipInputStream

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

        // YOLOv8 COCO 80类默认标签
        private val DEFAULT_COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
            "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
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
    private lateinit var seekbarConfidence: SeekBar
    private lateinit var seekbarNms: SeekBar
    private lateinit var tvConfValue: TextView
    private lateinit var tvNmsValue: TextView
    private lateinit var captureRegionSpinner: Spinner

    // 截屏区域显示切换按钮
    private lateinit var btnToggleCaptureRegionDisplay: Button
    private var isCaptureRegionDisplayShown = false

    // 截屏区域选项
    private val captureRegionOptions = listOf("全屏", "256×256", "320×320", "640×640", "居中75%")

    // 当前截屏区域（用于检测框坐标偏移）
    @Volatile
    private var currentCaptureRegion: CaptureRegion = CaptureRegion.FULL_SCREEN

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

            // 服务连接后应用截屏区域（必须在captureService赋值后调用）
            applyCaptureRegion()

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

    // 选择zip压缩包的launcher
    private val pickZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            updateStatus("正在解析压缩包...")
            importFromZip(it)
        }
    }

    // 选择param文件的launcher（单独文件模式）
    private val pickParamLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            pendingParamUri = it
            updateStatus("已选择param文件，请选择bin文件")
            pickBinLauncher.launch(arrayOf("*/*"))
        }
    }

    // 选择bin文件的launcher
    private var pendingParamUri: Uri? = null
    private val pickBinLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { binUri ->
            val paramUri = pendingParamUri
            if (paramUri != null) {
                // param和bin都选完了，弹出命名对话框
                showModelNameDialog(paramUri, binUri)
            }
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

        // 悬浮窗诊断日志回调
        overlayManager.onLog = { msg ->
            logger.info(TAG, "OverlayView: $msg")
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
        seekbarConfidence = findViewById(R.id.seekbar_confidence)
        seekbarNms = findViewById(R.id.seekbar_nms)
        tvConfValue = findViewById(R.id.tv_conf_value)
        tvNmsValue = findViewById(R.id.tv_nms_value)
        captureRegionSpinner = findViewById(R.id.spinner_capture_region)
        btnToggleCaptureRegionDisplay = findViewById(R.id.btn_toggle_capture_region_display)

        // 初始化截屏区域下拉框
        val regionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, captureRegionOptions)
        regionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        captureRegionSpinner.adapter = regionAdapter

        // 置信度阈值滑块：0-100 映射到 0.0-1.0
        seekbarConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvConfValue.text = String.format("%.2f", value)
                // 实时更新推理引擎的置信度阈值
                if (isInferenceReady) {
                    inferenceEngine.setConfidenceThreshold(value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // NMS阈值滑块：0-100 映射到 0.0-1.0
        seekbarNms.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvNmsValue.text = String.format("%.2f", value)
                // 实时更新推理引擎的NMS阈值
                if (isInferenceReady) {
                    inferenceEngine.setNmsThreshold(value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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
        // 导入模型按钮 - 弹出选择导入方式对话框
        btnImportModel.setOnClickListener {
            showImportMethodDialog()
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

        // 长按模型Spinner编辑配置
        modelSpinner.setOnLongClickListener {
            val modelName = modelSpinner.selectedItem?.toString()
            if (modelName != null && modelManager.availableModels.contains(modelName)) {
                showModelConfigEditor(modelName)
            } else {
                Toast.makeText(this, "请先选择一个模型", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // 截屏区域显示切换按钮
        btnToggleCaptureRegionDisplay.setOnClickListener {
            isCaptureRegionDisplayShown = !isCaptureRegionDisplayShown
            overlayManager.setShowCaptureRegion(isCaptureRegionDisplayShown)
            overlayManager.setCaptureRegion(currentCaptureRegion)
            btnToggleCaptureRegionDisplay.text = if (isCaptureRegionDisplayShown) "隐藏区域" else "显示区域"
        }
    }

    /**
     * 显示导入方式选择对话框
     * 支持zip压缩包导入和单独文件导入
     */
    private fun showImportMethodDialog() {
        val options = arrayOf(
            "从ZIP压缩包导入（推荐）",
            "分别选择param和bin文件"
        )

        AlertDialog.Builder(this)
            .setTitle("选择导入方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        updateStatus("请选择模型ZIP压缩包")
                        pickZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    }
                    1 -> {
                        updateStatus("请选择模型param文件")
                        pendingParamUri = null
                        pickParamLauncher.launch(arrayOf("*/*"))
                    }
                }
            }
            .show()
    }

    /**
     * 从ZIP压缩包导入模型
     * 自动解压并查找param、bin、config文件
     * config.json可选，没有时使用默认配置
     */
    private fun importFromZip(zipUri: Uri) {
        try {
            val tempDir = File(cacheDir, "model_import_temp")
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            var paramFile: File? = null
            var binFile: File? = null
            var configFile: File? = null

            // 解压ZIP，查找模型文件
            contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val entryName = entry.name.lowercase()
                        // 跳过目录和隐藏文件
                        if (!entry.isDirectory && !entryName.contains("__macosx") && !entryName.startsWith(".")) {
                            val fileName = File(entry.name).name

                            when {
                                // 匹配param文件（.param）
                                entryName.endsWith(".param") && paramFile == null -> {
                                    val dest = File(tempDir, fileName)
                                    dest.outputStream().use { output -> zipStream.copyTo(output) }
                                    paramFile = dest
                                }
                                // 匹配bin文件（.bin）
                                entryName.endsWith(".bin") && binFile == null -> {
                                    val dest = File(tempDir, fileName)
                                    dest.outputStream().use { output -> zipStream.copyTo(output) }
                                    binFile = dest
                                }
                                // 匹配config文件（.json）
                                entryName.endsWith(".json") && configFile == null -> {
                                    val dest = File(tempDir, fileName)
                                    dest.outputStream().use { output -> zipStream.copyTo(output) }
                                    configFile = dest
                                }
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }

            // 校验必需文件
            if (paramFile == null || binFile == null) {
                val missing = mutableListOf<String>()
                if (paramFile == null) missing.add(".param文件")
                if (binFile == null) missing.add(".bin文件")
                Toast.makeText(this, "压缩包中未找到: ${missing.joinToString("、")}", Toast.LENGTH_LONG).show()
                updateStatus("导入失败：缺少模型文件")
                tempDir.deleteRecursively()
                return
            }

            // config可选，没有时提示使用默认配置
            if (configFile == null) {
                AlertDialog.Builder(this)
                    .setTitle("未找到config.json")
                    .setMessage("压缩包中没有config.json配置文件。\n\n" +
                            "config.json用于告诉推理引擎：\n" +
                            "• 模型输入尺寸（默认640×640）\n" +
                            "• 检测类别数量（默认80类COCO）\n" +
                            "• 类别标签名称\n" +
                            "• 置信度/NMS阈值\n\n" +
                            "是否使用YOLOv8默认配置（640×640，COCO 80类）？")
                    .setPositiveButton("使用默认配置") { _, _ ->
                        showModelNameDialogWithFiles(paramFile!!, binFile!!, null)
                    }
                    .setNegativeButton("取消导入") { _, _ ->
                        tempDir.deleteRecursively()
                        updateStatus("已取消导入")
                    }
                    .show()
            } else {
                showModelNameDialogWithFiles(paramFile!!, binFile!!, configFile)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "解压失败: ${e.message}", Toast.LENGTH_SHORT).show()
            updateStatus("导入失败")
            logger.error(TAG, "Error importing from zip", e)
        }
    }

    /**
     * 显示模型命名对话框（单独文件模式）
     */
    private fun showModelNameDialog(paramUri: Uri, binUri: Uri) {
        val input = EditText(this).apply {
            hint = "例如: yolov8n_custom"
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("为模型命名")
            .setMessage("请输入导入模型的名称（不能与已有模型重名）\n\n" +
                    "注意：未选择config.json，将使用YOLOv8默认配置\n" +
                    "（640×640，COCO 80类，置信度0.5，NMS 0.4）")
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
                performImportFromUris(modelName, paramUri, binUri, null)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示模型命名对话框（ZIP解压后模式）
     * 支持选择YOLO版本以生成正确的默认config
     */
    private fun showModelNameDialogWithFiles(paramFile: File, binFile: File, configFile: File?) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val nameInput = EditText(this).apply {
            hint = "例如: yolov8n_custom"
        }
        dialogView.addView(nameInput)

        // 如果没有config，让用户选择YOLO版本
        if (configFile == null) {
            val versionLabel = TextView(this).apply {
                text = "选择YOLO版本："
                setPadding(0, 16, 0, 8)
            }
            dialogView.addView(versionLabel)

            val radioGroup = RadioGroup(this)
            val versions = listOf("YOLOv5", "YOLOv7", "YOLOv8")
            versions.forEachIndexed { index, version ->
                RadioButton(this).apply {
                    text = version
                    id = index
                    if (index == 2) isChecked = true // 默认选中YOLOv8
                }.also { radioGroup.addView(it) }
            }
            radioGroup.id = View.generateViewId()
            dialogView.addView(radioGroup)
        }

        AlertDialog.Builder(this)
            .setTitle("为模型命名")
            .setView(dialogView)
            .setPositiveButton("导入") { _, _ ->
                val modelName = nameInput.text.toString().trim()
                if (modelName.isEmpty()) {
                    Toast.makeText(this, "模型名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (modelManager.availableModels.contains(modelName)) {
                    Toast.makeText(this, "模型名称已存在，请换一个", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 获取选择的YOLO版本
                var yoloVersion = "yolov8"
                if (configFile == null) {
                    val radioGroup = dialogView.getChildAt(dialogView.childCount - 1) as? RadioGroup
                    val selectedId = radioGroup?.checkedRadioButtonId ?: 2
                    yoloVersion = when (selectedId) {
                        0 -> "yolov5"
                        1 -> "yolov7"
                        else -> "yolov8"
                    }
                }

                performImportFromFiles(modelName, paramFile, binFile, configFile, yoloVersion)
            }
            .setNegativeButton("取消") { _, _ ->
                // 清理临时文件
                val tempDir = File(cacheDir, "model_import_temp")
                tempDir.deleteRecursively()
                updateStatus("已取消导入")
            }
            .show()
    }

    /**
     * 执行模型导入（从ZIP解压的文件）
     */
    private fun performImportFromFiles(
        modelName: String,
        paramFile: File,
        binFile: File,
        configFile: File?,
        yoloVersion: String = "yolov8"
    ) {
        updateStatus("正在导入模型: $modelName ...")

        try {
            val modelDir = File(filesDir, "imported_models/$modelName")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            // 复制param文件
            val destParam = File(modelDir, "model.param")
            paramFile.inputStream().use { input -> destParam.outputStream().use { output -> input.copyTo(output) } }

            // 复制bin文件
            val destBin = File(modelDir, "model.bin")
            binFile.inputStream().use { input -> destBin.outputStream().use { output -> input.copyTo(output) } }

            // 处理config文件
            val destConfig = File(modelDir, "config.json")
            if (configFile != null) {
                // 有config文件，直接复制
                configFile.inputStream().use { input -> destConfig.outputStream().use { output -> input.copyTo(output) } }
            } else {
                // 没有config文件，生成默认配置
                generateDefaultConfig(destConfig, yoloVersion)
            }

            // 清理临时文件
            val tempDir = File(cacheDir, "model_import_temp")
            tempDir.deleteRecursively()

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

            // 导入成功后弹出配置编辑对话框
            showModelConfigEditor(modelName)
        } catch (e: Exception) {
            updateStatus("模型导入失败")
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            logger.error(TAG, "Error importing model: $modelName", e)
        }
    }

    /**
     * 执行模型导入（从SAF Uri，单独文件模式）
     * 没有config时使用默认配置
     */
    private fun performImportFromUris(modelName: String, paramUri: Uri, binUri: Uri, configUri: Uri?) {
        updateStatus("正在导入模型: $modelName ...")

        try {
            val modelDir = File(filesDir, "imported_models/$modelName")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            // 复制param文件
            val destParam = File(modelDir, "model.param")
            contentResolver.openInputStream(paramUri)?.use { input ->
                destParam.outputStream().use { output -> input.copyTo(output) }
            }

            // 复制bin文件
            val destBin = File(modelDir, "model.bin")
            contentResolver.openInputStream(binUri)?.use { input ->
                destBin.outputStream().use { output -> input.copyTo(output) }
            }

            // 处理config文件（可选）
            val destConfig = File(modelDir, "config.json")
            if (configUri != null) {
                contentResolver.openInputStream(configUri)?.use { input ->
                    destConfig.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                // 没有config，生成默认配置
                generateDefaultConfig(destConfig, "yolov8")
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

            // 导入成功后弹出配置编辑对话框
            showModelConfigEditor(modelName)
        } catch (e: Exception) {
            updateStatus("模型导入失败")
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            logger.error(TAG, "Error importing model: $modelName", e)
        }
    }

    /**
     * 生成默认的config.json配置文件
     * @param destFile 目标文件
     * @param yoloVersion YOLO版本（yolov5/yolov7/yolov8）
     */
    private fun generateDefaultConfig(destFile: File, yoloVersion: String) {
        val configJson = """
        {
            "version": "${yoloVersion}n",
            "inputWidth": 640,
            "inputHeight": 640,
            "numClasses": 80,
            "confidenceThreshold": 0.5,
            "nmsThreshold": 0.4,
            "labels": [
                ${DEFAULT_COCO_LABELS.joinToString(",\n                ") { "\"$it\"" }}
            ]
        }
        """.trimIndent()

        destFile.writeText(configJson)
        logger.info(TAG, "Generated default config for $yoloVersion")
    }

    /**
     * 显示模型配置编辑对话框
     * 允许用户编辑：YOLO版本、输入尺寸、类别数、置信度/NMS阈值、类别标签
     * @param modelName 模型名称
     */
    private fun showModelConfigEditor(modelName: String) {
        val config = modelManager.getModelConfig(modelName) ?: return
        val configFile = File(filesDir, "imported_models/$modelName/config.json")

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // 提示文字（放在ScrollView内部，避免setMessage导致按钮被截断）
        layout.addView(TextView(this).apply {
            text = "请检查并修改模型配置，确保与实际模型匹配："
            setTextColor(0xFF666666.toInt())
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        // YOLO版本
        layout.addView(TextView(this).apply { text = "YOLO版本（决定解码方式）:" })
        val versionRadioGroup = RadioGroup(this)
        val versions = listOf("yolov5", "yolov7", "yolov8")
        versions.forEachIndexed { index, version ->
            RadioButton(this).apply {
                text = version
                id = index
                // 根据当前config的version字段匹配
                val currentVersion = config.version.lowercase()
                isChecked = currentVersion.contains(version.removePrefix("yolo"))
            }.also { versionRadioGroup.addView(it) }
        }
        layout.addView(versionRadioGroup)

        // 输入宽度
        layout.addView(TextView(this).apply { text = "输入宽度:"; setPadding(0, 16, 0, 4) })
        val etWidth = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "640"
            setText(config.inputWidth.toString())
        }
        layout.addView(etWidth)

        // 输入高度
        layout.addView(TextView(this).apply { text = "输入高度:"; setPadding(0, 8, 0, 4) })
        val etHeight = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "640"
            setText(config.inputHeight.toString())
        }
        layout.addView(etHeight)

        // 类别数
        layout.addView(TextView(this).apply { text = "检测类别数:"; setPadding(0, 8, 0, 4) })
        val etNumClasses = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "80"
            setText(config.numClasses.toString())
        }
        layout.addView(etNumClasses)

        // 置信度阈值
        layout.addView(TextView(this).apply { text = "置信度阈值 (0-1):"; setPadding(0, 8, 0, 4) })
        val etConfThresh = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.5"
            setText(config.confidenceThreshold.toString())
        }
        layout.addView(etConfThresh)

        // NMS阈值
        layout.addView(TextView(this).apply { text = "NMS阈值 (0-1):"; setPadding(0, 8, 0, 4) })
        val etNmsThresh = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.4"
            setText(config.nmsThreshold.toString())
        }
        layout.addView(etNmsThresh)

        // 类别标签
        layout.addView(TextView(this).apply {
            text = "类别标签（每行一个，顺序对应类别ID）:"
            setPadding(0, 16, 0, 4)
        })
        val etLabels = EditText(this).apply {
            hint = "person\ncar\ndog\n..."
            setText(config.labels.joinToString("\n"))
            setLines(8)
            setMinLines(4)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(16, 8, 16, 8)
            setBackgroundResource(android.R.drawable.edit_text)
        }
        layout.addView(etLabels)

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("模型配置 - $modelName")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                // 读取编辑后的值
                val selectedVersionId = versionRadioGroup.checkedRadioButtonId
                val yoloVersion = when (selectedVersionId) {
                    0 -> "yolov5"
                    1 -> "yolov7"
                    else -> "yolov8"
                }
                val inputWidth = etWidth.text.toString().toIntOrNull() ?: 640
                val inputHeight = etHeight.text.toString().toIntOrNull() ?: 640
                val numClasses = etNumClasses.text.toString().toIntOrNull() ?: 80
                val confThresh = etConfThresh.text.toString().toFloatOrNull() ?: 0.5f
                val nmsThresh = etNmsThresh.text.toString().toFloatOrNull() ?: 0.4f
                val labels = etLabels.text.toString().lines().filter { it.isNotBlank() }

                // 如果标签数量与类别数不匹配，提示
                if (labels.size != numClasses && labels.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        "标签数量(${labels.size})与类别数($numClasses)不匹配，请检查",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // 生成新的config.json
                val newConfigJson = buildString {
                    append("{\n")
                    append("    \"version\": \"${yoloVersion}n\",\n")
                    append("    \"inputWidth\": $inputWidth,\n")
                    append("    \"inputHeight\": $inputHeight,\n")
                    append("    \"numClasses\": $numClasses,\n")
                    append("    \"confidenceThreshold\": $confThresh,\n")
                    append("    \"nmsThreshold\": $nmsThresh,\n")
                    append("    \"labels\": [\n")
                    labels.forEachIndexed { index, label ->
                        append("        \"$label\"")
                        if (index < labels.size - 1) append(",")
                        append("\n")
                    }
                    append("    ]\n")
                    append("}")
                }

                configFile.writeText(newConfigJson)

                // 刷新模型缓存
                modelManager.refreshModelList()

                updateStatus("模型配置已更新: $modelName")
                Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
                logger.info(TAG, "Model config updated: $modelName")
            }
            .setNegativeButton("跳过", null)
            .show()
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

        // 同步SeekBar到模型配置的阈值
        seekbarConfidence.progress = (config.confidenceThreshold * 100).toInt()
        seekbarNms.progress = (config.nmsThreshold * 100).toInt()
        tvConfValue.text = String.format("%.2f", config.confidenceThreshold)
        tvNmsValue.text = String.format("%.2f", config.nmsThreshold)

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

        // 绑定截屏服务以获取帧回调（applyCaptureRegion在onServiceConnected中调用）
        val bindIntent = Intent(this, ScreenCaptureService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 显示悬浮窗
        overlayManager.show()

        isCapturing = true
        updateStatus("截屏推理运行中")
        logger.info(TAG, "Capture and inference started")
    }

    /**
     * 根据UI选择应用截屏区域
     * 使用WindowManager获取实时屏幕尺寸，确保旋转后也能正确计算
     */
    private fun applyCaptureRegion() {
        // 优先使用CaptureManager的实时屏幕尺寸（旋转后会更新）
        // 回退到WindowManager查询（比resources.displayMetrics更可靠）
        val screenWidth: Int
        val screenHeight: Int
        val svcWidth = captureService?.currentScreenWidth ?: 0
        val svcHeight = captureService?.currentScreenHeight ?: 0
        if (svcWidth > 0 && svcHeight > 0) {
            screenWidth = svcWidth
            screenHeight = svcHeight
        } else {
            val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }

        val region = when (captureRegionSpinner.selectedItemPosition) {
            1 -> {
                // 256×256 居中
                val size = 256
                CaptureRegion((screenWidth - size) / 2, (screenHeight - size) / 2, size, size)
            }
            2 -> {
                // 320×320 居中
                val size = 320
                CaptureRegion((screenWidth - size) / 2, (screenHeight - size) / 2, size, size)
            }
            3 -> {
                // 640×640 居中
                val size = 640
                CaptureRegion((screenWidth - size) / 2, (screenHeight - size) / 2, size, size)
            }
            4 -> {
                // 居中75%区域
                val w = (screenWidth * 0.75).toInt()
                val h = (screenHeight * 0.75).toInt()
                CaptureRegion((screenWidth - w) / 2, (screenHeight - h) / 2, w, h)
            }
            else -> CaptureRegion.FULL_SCREEN
        }

        currentCaptureRegion = region
        captureService?.setCaptureRegion(region)
        // 同步截屏区域到overlay（用于绘制截屏范围框）
        overlayManager.setCaptureRegion(region)
        logger.info(TAG, "Capture region set: ${if (region.isFullScreen) "FULL_SCREEN" else "${region.width}x${region.height} at (${region.x},${region.y})"}")
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

    // 推理帧计数（诊断用）
    private var inferenceFrameCount = 0
    private var lastInferenceLogTime = 0L

    // 是否已记录过NCNN输出诊断信息
    private var hasLoggedNcnnDiag = false

    // 上次旋转检查时间（避免每帧都检查，每3秒检查一次）
    private var lastRotationCheckTime = 0L

    /**
     * 处理截屏帧回调
     * 将截屏帧送入推理引擎，然后将结果显示在悬浮窗上
     */
    private fun onCaptureFrame(bitmap: Bitmap, captureTimeMs: Long) {
        if (!isInferenceReady) {
            // 推理未就绪，回收Bitmap
            bitmap.recycle()
            return
        }

        try {
            // 定期检查屏幕旋转（每3秒检查一次，避免每帧都查询系统服务）
            val now = System.currentTimeMillis()
            if (now - lastRotationCheckTime >= 3000) {
                lastRotationCheckTime = now
                val rotated = captureService?.checkAndRecreateForRotation() ?: false
                if (rotated) {
                    // 屏幕旋转了，重新计算截屏区域
                    applyCaptureRegion()
                }
            }

            // 设置原图尺寸（用于坐标映射：原图像素→屏幕像素）
            // 全屏截屏时：bitmap就是全屏截图，直接用bitmap尺寸
            // 截屏区域时：检测框已偏移到全屏坐标，需要用全屏尺寸
            // 旋转后bitmap尺寸会自动变化（AUTO_MIRROR），但全屏尺寸需要实时查询
            if (!currentCaptureRegion.isFullScreen) {
                val curWidth = captureService?.currentScreenWidth ?: 0
                val curHeight = captureService?.currentScreenHeight ?: 0
                if (curWidth > 0 && curHeight > 0) {
                    overlayManager.setSourceSize(curWidth, curHeight)
                } else {
                    // 回退到displayMetrics
                    val displayMetrics = resources.displayMetrics
                    overlayManager.setSourceSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
                }
            } else {
                // 全屏截屏：bitmap尺寸就是源尺寸，旋转后bitmap会自动变为新方向
                overlayManager.setSourceSize(bitmap.width, bitmap.height)
            }

            // 执行推理
            val (detections, metrics) = inferenceEngine.inferWithMetrics(bitmap)

            // 如果使用了截屏区域，检测框坐标需要加上区域偏移量
            // 因为推理是在裁剪后的Bitmap上进行的，坐标是相对于裁剪区域的
            // 但overlay显示在全屏上，需要将坐标映射回全屏空间
            val captureRegion = currentCaptureRegion
            val offsetDetections = if (!captureRegion.isFullScreen) {
                detections.map { det ->
                    det.copy(
                        x1 = det.x1 + captureRegion.x,
                        y1 = det.y1 + captureRegion.y,
                        x2 = det.x2 + captureRegion.x,
                        y2 = det.y2 + captureRegion.y
                    )
                }
            } else {
                detections
            }

            // 首次推理时记录NCNN输出诊断信息到应用日志
            if (!hasLoggedNcnnDiag) {
                val engine = inferenceEngine
                if (engine is NcnnInferenceEngine) {
                    val diagInfo = engine.lastOutputDiagInfo
                    if (diagInfo.isNotEmpty()) {
                        hasLoggedNcnnDiag = true
                        logger.info(TAG, "=== NCNN Output Diagnostic ===")
                        diagInfo.lines().forEach { line ->
                            logger.info(TAG, line)
                        }
                        // 也记录前3个检测的原始坐标
                        offsetDetections.take(3).forEachIndexed { idx, det ->
                            logger.info(TAG, "  det[$idx]: x1=${det.x1}, y1=${det.y1}, x2=${det.x2}, y2=${det.y2}, label=${det.label}, conf=${det.confidence}")
                        }
                    }
                }
            }

            // 更新悬浮窗（使用偏移后的检测框坐标）
            overlayManager.updateDetections(offsetDetections, metrics.copy(captureLatencyMs = captureTimeMs))

            // 更新UI上的性能指标
            updateMetricsUI(metrics)

            // 诊断日志：每3秒打印一次推理统计
            inferenceFrameCount++
            val inferNow = System.currentTimeMillis()
            if (inferNow - lastInferenceLogTime >= 3000) {
                val elapsed = inferNow - lastInferenceLogTime
                val fps = inferenceFrameCount * 1000f / elapsed
                logger.info(TAG, "Inference stats: ${inferenceFrameCount} frames in ${elapsed}ms (${"%.1f".format(fps)} fps), detections=${detections.size}, latency=${metrics.inferenceLatencyMs}ms")
                inferenceFrameCount = 0
                lastInferenceLogTime = inferNow
            }

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
