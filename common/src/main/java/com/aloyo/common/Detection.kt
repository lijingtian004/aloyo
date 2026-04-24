package com.aloyo.common

/**
 * 检测结果数据类
 * 包含边界框、标签、置信度等关键信息
 */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val label: String,
    val confidence: Float,
    val classId: Int
)
