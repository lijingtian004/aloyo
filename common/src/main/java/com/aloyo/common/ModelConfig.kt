package com.aloyo.common

/**
 * 模型配置数据类
 * 包含模型版本、输入尺寸、类别数、阈值等参数
 */
data class ModelConfig(
    val version: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val numClasses: Int,
    val confidenceThreshold: Float = 0.5f,
    val nmsThreshold: Float = 0.4f,
    val labels: List<String> = emptyList(),
    /**
     * 检测框缩放因子
     * 用于补偿锚框不匹配或模型回归偏差导致的检测框偏小问题
     * 1.0 = 不缩放，1.2 = 每边扩展20%（框面积增加约44%）
     * 推荐值：1.0~1.5，默认1.15（轻微扩展以完全框住目标）
     */
    val boxScale: Float = 1.15f
)
