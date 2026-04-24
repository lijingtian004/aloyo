package com.aloyo.common

/**
 * 性能指标数据类
 * 记录推理延迟和帧率等关键性能数据
 */
data class PerformanceMetrics(
    val inferenceLatencyMs: Long = 0,
    val fps: Float = 0f,
    val captureLatencyMs: Long = 0,
    val totalLatencyMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)
