package com.aloyo.common

/**
 * 悬浮窗渲染器接口
 * 定义检测结果在悬浮窗上的渲染方式
 */
interface IOverlayRenderer {

    // 判断悬浮窗是否显示中
    val isShowing: Boolean

    /**
     * 显示悬浮窗
     */
    fun show()

    /**
     * 隐藏悬浮窗
     */
    fun hide()

    /**
     * 更新检测结果
     * @param detections 检测结果列表
     * @param metrics 性能指标
     */
    fun updateDetections(detections: List<Detection>, metrics: PerformanceMetrics)

    /**
     * 设置悬浮窗配置
     * @param config 渲染配置
     */
    fun setConfig(config: OverlayConfig)
}

/**
 * 悬浮窗渲染配置
 */
data class OverlayConfig(
    val boxColor: Int = 0xFF00FF00.toInt(),
    val boxStrokeWidth: Float = 3f,
    val textSize: Float = 14f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textBgColor: Int = 0x99000000.toInt(),
    val showConfidence: Boolean = true,
    val showFps: Boolean = true,
    val showLatency: Boolean = true,
    val opacity: Float = 0.9f
)
