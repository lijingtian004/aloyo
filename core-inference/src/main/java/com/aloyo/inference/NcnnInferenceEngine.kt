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

            // 根据模型版本创建对应的后处理器
            postProcessor = when {
                config.version.contains("v5", ignoreCase = true) ->
                    YoloPostProcessor(YoloV5Decoder(), config, confidenceThreshold, nmsThreshold)
                config.version.contains("v7", ignoreCase = true) ->
                    YoloPostProcessor(YoloV7Decoder(), config, confidenceThreshold, nmsThreshold)
                config.version.contains("v8", ignoreCase = true) ->
                    YoloPostProcessor(YoloV8Decoder(), config, confidenceThreshold, nmsThreshold)
                else -> {
                    android.util.Log.w(TAG, "Unknown YOLO version: ${config.version}, defaulting to v8 decoder")
                    YoloPostProcessor(YoloV8Decoder(), config, confidenceThreshold, nmsThreshold)
                }
            }

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

        // 预处理：缩放、归一化
        val inputData = processor.process(bitmap)

        // 执行NCNN推理
        val outputData = nativeRunInference(netPtr, inputData, modelConfig!!.inputWidth, modelConfig!!.inputHeight)

        if (outputData == null) {
            android.util.Log.w(TAG, "NCNN inference returned null")
            return emptyList()
        }

        // 后处理：解码、NMS
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height
        return postProc.process(outputData, srcWidth, srcHeight)
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
     * 释放NCNN模型
     */
    private external fun nativeReleaseModel(netPtr: Long)
}
