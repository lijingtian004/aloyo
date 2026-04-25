package com.aloyo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.view.View
import com.aloyo.common.Detection
import com.aloyo.common.OverlayConfig
import com.aloyo.common.PerformanceMetrics

/**
 * 检测结果悬浮窗视图
 * 在屏幕上绘制检测框、标签、置信度和性能指标
 * 使用FLAG_NOT_TOUCHABLE，不拦截触摸事件
 */
class DetectionOverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "DetectionOverlayView"
    }

    // 当前检测结果
    @Volatile
    private var detections: List<Detection> = emptyList()

    // 当前性能指标
    @Volatile
    private var metrics: PerformanceMetrics? = null

    // 渲染配置
    private var config: OverlayConfig = OverlayConfig()

    // 是否曾接收过数据
    @Volatile
    private var hasReceivedData: Boolean = false

    // 绘制画笔 - 使用醒目的颜色确保可见
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.RED
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")
    }

    private val metricsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        color = Color.GREEN
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val metricsBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")
    }

    // 等待数据提示的画笔
    private val waitingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = Color.YELLOW
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // 视图尺寸
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    // 缩放比例（从模型坐标到屏幕坐标）
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    /**
     * 更新检测结果
     */
    fun updateDetections(detections: List<Detection>, metrics: PerformanceMetrics? = null) {
        this.detections = detections
        this.metrics = metrics
        this.hasReceivedData = true
        invalidate()
    }

    /**
     * 设置渲染配置
     */
    fun setOverlayConfig(config: OverlayConfig) {
        this.config = config
        boxPaint.strokeWidth = config.boxStrokeWidth
        boxPaint.color = config.boxColor
        textPaint.textSize = config.textSize
        textPaint.color = config.textColor
        textBgPaint.color = config.textBgColor
        invalidate()
    }

    /**
     * 设置视图尺寸和缩放比例
     */
    fun setViewSize(width: Int, height: Int, modelWidth: Int, modelHeight: Int) {
        viewWidth = width
        viewHeight = height
        scaleX = width.toFloat() / modelWidth
        scaleY = height.toFloat() / modelHeight
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 清除之前的内容
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 如果还没有接收过数据，显示等待提示
        if (!hasReceivedData) {
            drawWaitingHint(canvas)
            return
        }

        // 绘制检测框
        for (detection in detections) {
            drawDetection(canvas, detection)
        }

        // 绘制性能指标
        metrics?.let { drawMetrics(canvas, it) }
    }

    /**
     * 绘制等待数据提示
     * 在屏幕左上角显示"等待数据..."提示
     */
    private fun drawWaitingHint(canvas: Canvas) {
        val hintText = "ALOYO 等待截屏数据..."
        val textWidth = waitingTextPaint.measureText(hintText)
        val textHeight = waitingTextPaint.fontMetrics.let { it.descent - it.ascent }
        val bgLeft = 8f
        val bgTop = 8f
        val bgRight = bgLeft + textWidth + 16f
        val bgBottom = bgTop + textHeight + 8f

        // 绘制背景
        canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, metricsBgPaint)
        // 绘制文字
        canvas.drawText(hintText, bgLeft + 8f, bgTop + textHeight, waitingTextPaint)
    }

    /**
     * 绘制单个检测结果
     */
    private fun drawDetection(canvas: Canvas, detection: Detection) {
        val x1 = detection.x1 * scaleX
        val y1 = detection.y1 * scaleY
        val x2 = detection.x2 * scaleX
        val y2 = detection.y2 * scaleY

        // 绘制边界框
        canvas.drawRect(x1, y1, x2, y2, boxPaint)

        // 绘制标签背景和文字
        val labelText = if (config.showConfidence) {
            "${detection.label} ${(detection.confidence * 100).toInt()}%"
        } else {
            detection.label
        }

        val textWidth = textPaint.measureText(labelText)
        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }

        // 标签位置（在边界框上方）
        val labelX = x1
        val labelY = y1 - textHeight - 4f

        // 绘制标签背景
        canvas.drawRect(
            labelX,
            labelY,
            labelX + textWidth + 8f,
            labelY + textHeight + 4f,
            textBgPaint
        )

        // 绘制标签文字
        canvas.drawText(labelText, labelX + 4f, labelY + textHeight, textPaint)
    }

    /**
     * 绘制性能指标（左上角）
     */
    private fun drawMetrics(canvas: Canvas, metrics: PerformanceMetrics) {
        val lines = mutableListOf<String>()
        if (config.showFps) {
            lines.add("FPS: ${"%.1f".format(metrics.fps)}")
        }
        if (config.showLatency) {
            lines.add("延迟: ${metrics.inferenceLatencyMs}ms")
        }
        if (lines.isEmpty()) return

        // 计算背景区域
        val lineHeight = metricsPaint.fontMetrics.let { it.descent - it.ascent } + 4f
        val maxWidth = lines.maxOf { metricsPaint.measureText(it) }
        val bgLeft = 8f
        val bgTop = 8f
        val bgRight = bgLeft + maxWidth + 16f
        val bgBottom = bgTop + lineHeight * lines.size + 8f

        // 绘制背景
        canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, metricsBgPaint)

        // 绘制文字
        lines.forEachIndexed { index, line ->
            val y = bgTop + lineHeight * (index + 1)
            canvas.drawText(line, bgLeft + 8f, y, metricsPaint)
        }
    }
}
