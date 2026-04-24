package com.aloyo.common

import android.graphics.Bitmap

/**
 * 推理引擎统一接口
 * 所有推理引擎（NCNN等）必须实现此接口
 */
interface IInferenceEngine {

    // 判断引擎是否已初始化
    val isInitialized: Boolean

    // 获取当前模型配置
    val modelConfig: ModelConfig?

    /**
     * 初始化推理引擎
     * @param paramPath 模型参数文件路径
     * @param binPath 模型权重文件路径
     * @param config 模型配置
     * @return 初始化是否成功
     */
    fun initialize(paramPath: String, binPath: String, config: ModelConfig): Boolean

    /**
     * 执行推理
     * @param bitmap 输入图像
     * @return 检测结果列表
     */
    fun infer(bitmap: Bitmap): List<Detection>

    /**
     * 执行推理并返回性能指标
     * @param bitmap 输入图像
     * @return 推理结果和性能指标的配对
     */
    fun inferWithMetrics(bitmap: Bitmap): Pair<List<Detection>, PerformanceMetrics>

    /**
     * 释放引擎资源
     */
    fun release()

    /**
     * 设置置信度阈值
     */
    fun setConfidenceThreshold(threshold: Float)

    /**
     * 设置NMS阈值
     */
    fun setNmsThreshold(threshold: Float)
}
