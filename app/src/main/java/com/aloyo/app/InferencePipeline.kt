package com.aloyo.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import com.aloyo.capture.ScreenCaptureService
import com.aloyo.common.CaptureRegion
import com.aloyo.common.Detection
import com.aloyo.common.IInferenceEngine
import com.aloyo.common.PerformanceMetrics
import com.aloyo.inference.NcnnInferenceEngine
import com.aloyo.model.ModelManager
import com.aloyo.overlay.OverlayManager

/**
 * 推理流水线控制器
 * 连接截屏采集→模型推理→悬浮窗显示的完整数据流
 * 管理截屏帧的接收、推理和结果展示
 */
class InferencePipeline(
    private val context: Context,
    private val inferenceEngine: IInferenceEngine,
    private val overlayManager: OverlayManager
) {
    companion object {
        private const val TAG = "InferencePipeline"
    }

    // 运行状态
    @Volatile
    var isRunning: Boolean = false
        private set

    // 性能统计
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var totalInferenceTime = 0L
    private var inferenceCount = 0

    // 帧回调（连接截屏和推理）
    private val frameCallback = object : com.aloyo.common.ICaptureSource.FrameCallback {
        override fun onFrame(bitmap: Bitmap, captureTimeMs: Long) {
            processFrame(bitmap, captureTimeMs)
        }

        override fun onError(error: String) {
            android.util.Log.e(TAG, "Capture error: $error")
        }
    }

    /**
     * 启动推理流水线
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()
        android.util.Log.i(TAG, "Inference pipeline started")
    }

    /**
     * 停止推理流水线
     */
    fun stop() {
        isRunning = false
        android.util.Log.i(TAG, "Inference pipeline stopped")
    }

    /**
     * 处理单帧
     * 核心数据流：截屏帧 → 推理 → 结果显示
     */
    private fun processFrame(bitmap: Bitmap, captureTimeMs: Long) {
        if (!isRunning) {
            bitmap.recycle()
            return
        }

        val startTime = System.currentTimeMillis()

        try {
            // 执行推理
            val (detections, metrics) = inferenceEngine.inferWithMetrics(bitmap)

            val endTime = System.currentTimeMillis()
            val totalLatency = endTime - startTime

            // 更新性能统计
            updatePerformanceStats(totalLatency)

            // 构建完整性能指标
            val fullMetrics = PerformanceMetrics(
                inferenceLatencyMs = metrics.inferenceLatencyMs,
                fps = currentFps,
                captureLatencyMs = captureTimeMs,
                totalLatencyMs = totalLatency + captureTimeMs
            )

            // 更新悬浮窗显示
            overlayManager.updateDetections(detections, fullMetrics)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error processing frame", e)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 更新性能统计
     */
    private fun updatePerformanceStats(inferenceTimeMs: Long) {
        frameCount++
        inferenceCount++
        totalInferenceTime += inferenceTimeMs

        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastFpsTime

        // 每秒更新一次FPS
        if (elapsed >= 1000) {
            currentFps = frameCount * 1000f / elapsed
            frameCount = 0
            lastFpsTime = currentTime
        }
    }

    /**
     * 获取当前平均推理延迟
     */
    fun getAverageInferenceLatency(): Long {
        return if (inferenceCount > 0) totalInferenceTime / inferenceCount else 0
    }

    /**
     * 获取当前FPS
     */
    fun getCurrentFps(): Float = currentFps
}
