package com.aloyo.inference

import android.graphics.Bitmap
import com.aloyo.common.Detection
import com.aloyo.common.IInferenceEngine
import com.aloyo.common.ModelConfig
import com.aloyo.common.PerformanceMetrics

/**
 * NCNN推理引擎实现
 * 封装NCNN的模型加载、推理和结果解析
 * 支持YOLOv5/v7/v8等多版本模型
 */
class NcnnInferenceEngine : IInferenceEngine {

    companion object {
        private const val TAG = "NcnnInference"

        // 标记本地库是否加载成功
        @Volatile
        private var nativeLibLoaded = false

        init {
            try {
                // NCNN已静态链接到aloyo_inference中，只需加载一个库
                System.loadLibrary("aloyo_inference")
                nativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w(TAG, "Native libraries not loaded, NCNN inference unavailable", e)
                nativeLibLoaded = false
            }
        }

        // 检查本地库是否可用
        fun isNativeAvailable(): Boolean = nativeLibLoaded
    }

    // NCNN Net对象，通过JNI操作
    private var netPtr: Long = 0L

    @Volatile
    override var isInitialized: Boolean = false
        private set

    @Volatile
    override var modelConfig: ModelConfig? = null
        private set

    // 置信度阈值和NMS阈值
    private var confidenceThreshold: Float = 0.5f
    private var nmsThreshold: Float = 0.4f

    // 预处理器和后处理器
    private var preProcessor: YoloPreProcessor? = null
    private var postProcessor: YoloPostProcessor? = null
    private var decoder: UnifiedYoloDecoder? = null

    // 诊断信息：上次推理的NCNN输出形状和原始值（用于日志诊断）
    @Volatile
    var lastOutputDiagInfo: String = ""
        private set

    // 是否已记录过输出诊断（仅首次推理时记录详细值）
    @Volatile
    private var hasLoggedOutputDiag: Boolean = false

    // 上次推理的输出blob形状信息
    @Volatile
    private var lastBlobShapes: List<OutputBlobInfo> = emptyList()

    override fun initialize(paramPath: String, binPath: String, config: ModelConfig): Boolean {
        if (isInitialized) {
            release()
        }

        // 检查本地库是否可用
        if (!isNativeAvailable()) {
            android.util.Log.e(TAG, "Native libraries not available, cannot initialize")
            return false
        }

        try {
            // 加载NCNN模型
            netPtr = nativeLoadModel(paramPath, binPath)
            if (netPtr == 0L) {
                android.util.Log.e(TAG, "Failed to load NCNN model")
                return false
            }

            modelConfig = config
            confidenceThreshold = config.confidenceThreshold
            nmsThreshold = config.nmsThreshold

            // 初始化预处理器
            preProcessor = YoloPreProcessor(config.inputWidth, config.inputHeight)

            // 根据模型版本创建对应的后处理器（统一解码器自动检测输出格式）
            val dec = UnifiedYoloDecoder()
            postProcessor = YoloPostProcessor(dec, config, confidenceThreshold, nmsThreshold)
            this.decoder = dec

            isInitialized = true
            android.util.Log.i(TAG, "NCNN engine initialized with model: ${config.version}")
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing NCNN engine", e)
            return false
        }
    }

    override fun infer(bitmap: Bitmap): List<Detection> {
        if (!isInitialized || netPtr == 0L) {
            android.util.Log.w(TAG, "Engine not initialized")
            return emptyList()
        }

        val processor = preProcessor ?: return emptyList()
        val postProc = postProcessor ?: return emptyList()

        // 当输入bitmap尺寸小于config.inputSize时，直接使用bitmap尺寸作为预处理目标
        // 避免不必要的放大（如256→640），因为模型内部会缩放回实际训练尺寸
        // 256→640→256的有损往返会降低检测精度
        val actualTargetWidth = minOf(bitmap.width, modelConfig!!.inputWidth)
        val actualTargetHeight = minOf(bitmap.height, modelConfig!!.inputHeight)
        val useActualSize = actualTargetWidth != modelConfig!!.inputWidth || actualTargetHeight != modelConfig!!.inputHeight

        if (useActualSize) {
            android.util.Log.i(TAG, "Using actual input size ${actualTargetWidth}x${actualTargetHeight} instead of config ${modelConfig!!.inputWidth}x${modelConfig!!.inputHeight}")
        }

        // 预处理：缩放、归一化（使用实际目标尺寸）
        val actualPreProcessor = if (useActualSize) {
            YoloPreProcessor(actualTargetWidth, actualTargetHeight)
        } else {
            processor
        }
        val inputData = actualPreProcessor.process(bitmap)

        // 执行NCNN推理（使用实际目标尺寸）
        val outputData = nativeRunInference(netPtr, inputData, actualTargetWidth, actualTargetHeight)

        if (outputData == null) {
            android.util.Log.w(TAG, "NCNN inference returned null")
            return emptyList()
        }

        // 构建输出诊断信息
        val numRows = outputData.size
        val numCols = if (numRows > 0) outputData[0].size else 0
        val config = modelConfig
        val v8Attrs = if (config != null) 4 + config.numClasses else -1
        val v5Attrs = if (config != null) 5 + config.numClasses else -1

        val diagBuilder = StringBuilder()
        diagBuilder.append("NCNN output shape: [$numRows x $numCols]")
        diagBuilder.append(", numClasses=${config?.numClasses}, v8Attrs=$v8Attrs, v5Attrs=$v5Attrs")

        // 首次推理时记录每个通道的前5个原始值
        val shouldLogDiag = !hasLoggedOutputDiag
        if (shouldLogDiag) {
            diagBuilder.append("\n")
            for (c in 0 until minOf(numRows, 8)) {
                val vals = (0 until minOf(numCols, 5)).joinToString(", ") {
                    String.format("%.4f", outputData[c][it])
                }
                diagBuilder.append("  ch[$c]: [$vals...]")
                if (c < minOf(numRows, 8) - 1) diagBuilder.append("\n")
            }

            // 也记录最后几个通道的值（可能是类别概率）
            if (numRows > 8) {
                diagBuilder.append("\n")
                for (c in maxOf(numRows - 4, 8) until numRows) {
                    val vals = (0 until minOf(numCols, 5)).joinToString(", ") {
                        String.format("%.4f", outputData[c][it])
                    }
                    diagBuilder.append("  ch[$c]: [$vals...]")
                    if (c < numRows - 1) diagBuilder.append("\n")
                }
            }
        }

        // 获取输出blob形状信息（从JNI层查询）
        val shapeArray = nativeGetLastOutputShape()
        if (shapeArray != null && shapeArray.size >= 1) {
            val numBlobs = shapeArray[0]
            val shapes = mutableListOf<OutputBlobInfo>()
            var idx = 1
            for (b in 0 until numBlobs) {
                if (idx + 2 < shapeArray.size) {
                    val c = shapeArray[idx]
                    val h = shapeArray[idx + 1]
                    val w = shapeArray[idx + 2]
                    shapes.add(OutputBlobInfo(channels = c, height = h, width = w))
                    idx += 3
                }
            }
            lastBlobShapes = shapes
            diagBuilder.append("\n  Blob shapes: $shapes")
        }

        lastOutputDiagInfo = diagBuilder.toString()
        android.util.Log.i(TAG, lastOutputDiagInfo)

        // 后处理：解码、NMS（传递blob形状信息和实际目标尺寸给后处理器）
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height
        val currentConfig = modelConfig ?: return emptyList()
        val rawDetections = decoder?.decode(outputData, currentConfig, confidenceThreshold, lastBlobShapes, actualTargetWidth) ?: emptyList()

        // 首次推理时记录解码器诊断信息到应用日志
        if (shouldLogDiag) {
            hasLoggedOutputDiag = true
            val decodeDiag = buildString {
                append("Decoder: skipObjectness=${decoder?.lastSkipObjectness}, rawDetections=${rawDetections.size}")
                if (rawDetections.isNotEmpty()) {
                    append("\n  Top detections:")
                    rawDetections.sortedByDescending { it.confidence }.take(5).forEachIndexed { idx, det ->
                        append("\n  [$idx] conf=${String.format("%.4f", det.confidence)}, logit=${String.format("%.4f", det.rawLogit)}, class=${det.classId}, cx=${String.format("%.1f", det.cx)}, cy=${String.format("%.1f", det.cy)}")
                    }
                }
            }
            lastOutputDiagInfo += "\n$decodeDiag"
        }

        val detections = postProc.processRawDetections(rawDetections, srcWidth, srcHeight, actualTargetWidth, actualTargetHeight)

        // 首次推理时记录前几个检测结果的坐标（诊断用）
        if (!hasLoggedOutputDiag || detections.isNotEmpty()) {
            if (detections.isNotEmpty()) {
                val sampleDets = detections.take(3).joinToString("; ") {
                    "(${String.format("%.1f", it.x1)},${String.format("%.1f", it.y1)})-(${String.format("%.1f", it.x2)},${String.format("%.1f", it.y2)}) ${it.label}:${String.format("%.2f", it.confidence)}"
                }
                android.util.Log.i(TAG, "Sample detections (${detections.size} total): $sampleDets")
            }
        }

        return detections
    }

    override fun inferWithMetrics(bitmap: Bitmap): Pair<List<Detection>, PerformanceMetrics> {
        val startTime = System.currentTimeMillis()

        val detections = infer(bitmap)

        val endTime = System.currentTimeMillis()
        val latencyMs = endTime - startTime

        val metrics = PerformanceMetrics(
            inferenceLatencyMs = latencyMs,
            fps = if (latencyMs > 0) 1000f / latencyMs else 0f,
            totalLatencyMs = latencyMs
        )

        return Pair(detections, metrics)
    }

    override fun release() {
        if (netPtr != 0L) {
            nativeReleaseModel(netPtr)
            netPtr = 0L
        }
        isInitialized = false
        modelConfig = null
        preProcessor = null
        postProcessor = null
        decoder = null
        android.util.Log.i(TAG, "NCNN engine released")
    }

    override fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold
        postProcessor?.setConfidenceThreshold(threshold)
    }

    override fun setNmsThreshold(threshold: Float) {
        nmsThreshold = threshold
        postProcessor?.setNmsThreshold(threshold)
    }

    // ============ JNI Native方法 ============

    /**
     * 加载NCNN模型
     * @return NCNN Net指针，0表示失败
     */
    private external fun nativeLoadModel(paramPath: String, binPath: String): Long

    /**
     * 执行推理
     * @param netPtr NCNN Net指针
     * @param inputData 预处理后的输入数据
     * @param width 输入宽度
     * @param height 输入高度
     * @return 输出数据数组，null表示失败
     */
    private external fun nativeRunInference(netPtr: Long, inputData: FloatArray, width: Int, height: Int): Array<FloatArray>?

    /**
     * 获取上次推理的输出blob形状信息
     * @return IntArray格式: [numBlobs, c0, h0, w0, c1, h1, w1, ...]，null表示无数据
     */
    private external fun nativeGetLastOutputShape(): IntArray?

    /**
     * 释放NCNN模型
     */
    private external fun nativeReleaseModel(netPtr: Long)
}
