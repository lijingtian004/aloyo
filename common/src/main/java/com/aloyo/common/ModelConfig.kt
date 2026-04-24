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
    val labels: List<String> = emptyList()
)
