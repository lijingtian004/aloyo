package com.aloyo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.View
import com.aloyo.common.Detection
import com.aloyo.common.OverlayConfig
import com.aloyo.common.PerformanceMetrics

/**
 * 检测结果悬浮窗视图
 * 在屏幕上绘制检测框、标签、置信度和性能指标
 * 使用FLAG_NOT_TOUCHABLE，不拦截触摸事件
 *
 * 坐标系统说明：
 * - Detection中的坐标是原图像素坐标（0到srcWidth/srcHeight）
 * - 本View的尺寸等于屏幕尺寸（由WindowManager.LayoutParams设置）
 * - 需要将原图坐标映射到屏幕坐标
 */
class DetectionOverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "DetectionOverlayView"
    }

    init {
        // 强制使用软件渲染，解决Android 15上硬件加速导致悬浮窗不可见的问题
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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

    // 原图尺寸（截屏图像的分辨率，用于坐标映射）
    @Volatile
    private var srcWidth: Int = 0
    @Volatile
    private var srcHeight: Int = 0

    // 是否显示截屏区域框
    @Volatile
    var showCaptureRegion: Boolean = false

    // 截屏区域（原图像素坐标）
    @Volatile
    private var captureRegion: com.aloyo.common.CaptureRegion = com.aloyo.common.CaptureRegion.FULL_SCREEN

    // 诊断：onDraw调用计数
    private var drawCount = 0
    private var lastDrawLogTime = 0L

    // 诊断回调（用于将日志写入应用日志文件）
    var onLog: ((String) -> Unit)? = null

    // 绘制画笔 - 检测框（红色醒目）
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.RED
    }

    // 标签文字
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // 标签背景
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#DD000000")
    }

    // 性能指标文字
    private val metricsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = Color.GREEN
        typeface = android.graphics.Typeface.MONOSPACE
    }

    // 性能指标背景
    private val metricsBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#DD000000")
    }

    // 等待数据提示
    private val waitingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        color = Color.YELLOW
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // 诊断边框画笔（确认悬浮窗可见）
    private val diagBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#44FF0000")
    }

    // 始终显示的状态指示器（用于确认悬浮窗可见）
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        color = Color.parseColor("#CCFFFFFF")
        typeface = android.graphics.Typeface.MONOSPACE
    }

    // 状态指示器背景
    private val statusBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#99000000")
    }

    // 截屏区域框画笔（青色虚线）
    private val captureRegionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.CYAN
        // 虚线效果
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    // 截屏区域遮罩画笔（半透明暗色，覆盖区域外部分）
    private val captureRegionMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66000000")
    }

    // 截屏区域标签画笔
    private val captureRegionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = Color.CYAN
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /**
     * 更新检测结果
     * @param detections 检测结果列表（坐标为原图像素坐标）
     * @param metrics 性能指标
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
        invalidate()
    }

    /**
     * 设置原图尺寸（用于坐标映射）
     */
    fun setSourceSize(srcWidth: Int, srcHeight: Int) {
        this.srcWidth = srcWidth
        this.srcHeight = srcHeight
    }

    /**
     * 设置截屏区域（用于绘制截屏范围框）
     */
    fun setCaptureRegion(region: com.aloyo.common.CaptureRegion) {
        this.captureRegion = region
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewW = width
        val viewH = height

        // 始终绘制诊断边框（确认悬浮窗可见）
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), diagBorderPaint)

        // 诊断：首次onDraw和每3秒打印一次
        drawCount++
        val now = System.currentTimeMillis()
        if (drawCount <= 3 || now - lastDrawLogTime >= 3000) {
            val scaleX = if (srcWidth > 0) viewW.toFloat() / srcWidth else 1f
            val scaleY = if (srcHeight > 0) viewH.toFloat() / srcHeight else 1f
            val sample = detections.firstOrNull()
            val msg = "onDraw: count=$drawCount, view=${viewW}x${viewH}, src=${srcWidth}x${srcHeight}, " +
                    "scale=${String.format("%.2f", scaleX)}x${String.format("%.2f", scaleY)}, " +
                    "dets=${detections.size}, hasData=$hasReceivedData, " +
                    "sample=${sample?.let { "x1=${it.x1},y1=${it.y1},x2=${it.x2},y2=${it.y2},label=${it.label}" } ?: "none"}"
            onLog?.invoke(msg)
            lastDrawLogTime = now
        }

        // 如果还没有接收过数据，显示等待提示
        if (!hasReceivedData) {
            drawWaitingHint(canvas)
            drawStatusIndicator(canvas, viewW, viewH)
            return
        }

        // 计算坐标映射比例：原图像素 → 屏幕像素
        // 检测框坐标是全屏像素坐标(0到srcWidth/srcHeight)
        // overlay窗口使用FLAG_LAYOUT_IN_SCREEN，从屏幕顶部(Y=0)开始
        // canvas的Y=0对应屏幕Y=0，所以直接用1:1映射（不缩放）
        // 如果canvas高度小于全屏高度（不含导航栏），超出部分自然裁剪
        val scaleX = if (srcWidth > 0) viewW.toFloat() / srcWidth else 1f
        val scaleY = if (srcHeight > 0) viewW.toFloat() / srcWidth else 1f
        // 保持等比缩放：使用相同的scale避免X/Y比例不一致导致框变形
        val uniformScale = if (srcWidth > 0 && srcHeight > 0) {
            minOf(viewW.toFloat() / srcWidth, viewH.toFloat() / srcHeight)
        } else 1f

        // 绘制检测框
        for (detection in detections) {
            drawDetection(canvas, detection, uniformScale, uniformScale)
        }

        // 绘制性能指标
        metrics?.let { drawMetrics(canvas, it) }

        // 绘制截屏区域框（如果启用且非全屏）
        if (showCaptureRegion && !captureRegion.isFullScreen && srcWidth > 0 && srcHeight > 0) {
            drawCaptureRegion(canvas, uniformScale, uniformScale, viewW, viewH)
        }

        // 始终绘制状态指示器
        drawStatusIndicator(canvas, viewW, viewH)
    }

    /**
     * 绘制截屏区域框
     * 在截屏区域外绘制半透明遮罩，区域内绘制青色虚线边框
     */
    private fun drawCaptureRegion(canvas: Canvas, scaleX: Float, scaleY: Float, viewW: Int, viewH: Int) {
        // 将截屏区域坐标映射到屏幕坐标
        val rx1 = captureRegion.x * scaleX
        val ry1 = captureRegion.y * scaleY
        val rx2 = (captureRegion.x + captureRegion.width) * scaleX
        val ry2 = (captureRegion.y + captureRegion.height) * scaleY

        // 绘制区域外遮罩（四个矩形：上、下、左、右）
        // 上方遮罩
        canvas.drawRect(0f, 0f, viewW.toFloat(), ry1, captureRegionMaskPaint)
        // 下方遮罩
        canvas.drawRect(0f, ry2, viewW.toFloat(), viewH.toFloat(), captureRegionMaskPaint)
        // 左方遮罩
        canvas.drawRect(0f, ry1, rx1, ry2, captureRegionMaskPaint)
        // 右方遮罩
        canvas.drawRect(rx2, ry1, viewW.toFloat(), ry2, captureRegionMaskPaint)

        // 绘制虚线边框
        canvas.drawRect(rx1, ry1, rx2, ry2, captureRegionPaint)

        // 绘制标签
        val labelText = "${captureRegion.width}×${captureRegion.height}"
        val textWidth = captureRegionLabelPaint.measureText(labelText)
        val textHeight = captureRegionLabelPaint.fontMetrics.let { it.descent - it.ascent }
        canvas.drawText(labelText, rx1 + 4f, ry1 - 4f, captureRegionLabelPaint)
    }

    /**
     * 绘制状态指示器（右下角小字，确认悬浮窗可见）
     */
    private fun drawStatusIndicator(canvas: Canvas, viewW: Int, viewH: Int) {
        val text = "ALOYO | ${detections.size} dets"
        val textWidth = statusPaint.measureText(text)
        val textHeight = statusPaint.fontMetrics.let { it.descent - it.ascent }
        val padding = 8f
        val x = viewW.toFloat() - textWidth - padding * 2 - 12f
        val y = viewH.toFloat() - textHeight - padding * 2 - 12f

        // 绘制背景
        canvas.drawRect(
            x - padding,
            y - padding,
            viewW.toFloat() - 12f + padding,
            viewH.toFloat() - 12f + padding,
            statusBgPaint
        )

        // 绘制文字
        canvas.drawText(text, x, y + textHeight, statusPaint)
    }

    /**
     * 绘制等待数据提示
     */
    private fun drawWaitingHint(canvas: Canvas) {
        val hintText = "ALOYO 等待截屏数据..."
        val textWidth = waitingTextPaint.measureText(hintText)
        val textHeight = waitingTextPaint.fontMetrics.let { it.descent - it.ascent }
        val bgLeft = 8f
        val bgTop = 8f
        val bgRight = bgLeft + textWidth + 16f
        val bgBottom = bgTop + textHeight + 8f

        canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, metricsBgPaint)
        canvas.drawText(hintText, bgLeft + 8f, bgTop + textHeight, waitingTextPaint)
    }

    /**
     * 绘制单个检测结果
     */
    private fun drawDetection(canvas: Canvas, detection: Detection, scaleX: Float, scaleY: Float) {
        // 将原图像素坐标映射到屏幕像素坐标
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
