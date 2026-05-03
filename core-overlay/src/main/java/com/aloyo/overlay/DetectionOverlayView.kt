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

    // 导航栏信息：是否可见及高度
    @Volatile
    private var isNavigationBarVisible: Boolean = false

    @Volatile
    private var navigationBarHeight: Int = 0

    // 屏幕旋转角度（0=竖屏, 90=横屏, 180=反向竖屏, 270=反向横屏）
    // 用于在 onDraw 中旋转 canvas，使绘制内容匹配实际显示方向
    @Volatile
    private var displayRotation: Int = 0

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

    /**
     * 设置导航栏信息
     * @param isVisible 导航栏（手势指示条）是否可见
     * @param height 导航栏高度（像素），0表示不可见或沉浸模式
     */
    fun setNavigationBarInfo(isVisible: Boolean, height: Int) {
        this.isNavigationBarVisible = isVisible
        this.navigationBarHeight = height
        invalidate()
    }

    /**
     * 设置屏幕旋转角度
     * 用于在 onDraw 中旋转 canvas，使绘制内容匹配实际显示方向
     * @param degrees 旋转角度（0, 90, 180, 270）
     */
    fun setDisplayRotation(degrees: Int) {
        if (this.displayRotation != degrees) {
            this.displayRotation = degrees
            // 必须在主线程执行 invalidate，避免 CalledFromWrongThreadException
            post { invalidate() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewW = width
        val viewH = height

        // 屏幕旋转处理：
        // overlay 窗口始终使用竖屏尺寸（如 1264x2780）
        // 当设备横屏时，系统会旋转显示内容，但 canvas 坐标系不变
        // 需要手动旋转 canvas 并变换坐标，使绘制内容匹配实际显示方向
        val rotation = displayRotation
        val isRotated = rotation == 90 || rotation == 270

        // 始终绘制诊断边框
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), diagBorderPaint)

        // 诊断日志
        drawCount++
        val now = System.currentTimeMillis()
        if (drawCount <= 3 || now - lastDrawLogTime >= 3000) {
            val sample = detections.firstOrNull()
            val msg = "onDraw: count=$drawCount, view=${viewW}x${viewH}, src=${srcWidth}x${srcHeight}, " +
                    "dets=${detections.size}, hasData=$hasReceivedData, rotation=$rotation, " +
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

        // overlay 始终竖屏 (1264x2780)，src 也始终竖屏 (1264x2780)
        // 设备旋转时，对坐标做变换后直接绘制在竖屏 canvas 上
        // 不使用 canvas rotation，避免位置错开

        // 绘制检测框
        for (detection in detections) {
            if (isRotated) {
                drawDetectionRotatedDirect(canvas, detection, rotation, srcWidth, srcHeight)
            } else {
                drawDetection(canvas, detection, 1f, 1f)
            }
        }

        // 绘制性能指标
        metrics?.let { drawMetrics(canvas, it) }

        // 绘制截屏区域框
        if (showCaptureRegion && !captureRegion.isFullScreen && srcWidth > 0 && srcHeight > 0) {
            if (isRotated) {
                drawCaptureRegionRotatedDirect(canvas, rotation, srcWidth, srcHeight)
            } else {
                drawCaptureRegion(canvas, 1f, 1f, viewW, viewH)
            }
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
     * 绘制横屏 overlay 上的竖屏截屏区域框（坐标变换后绘制）
     * 竖屏 (x,y) → 横屏 (viewW - regionH - y, x)
     */
    private fun drawCaptureRegionTransformed(canvas: Canvas, viewW: Int, viewH: Int) {
        val region = captureRegion
        // 竖屏坐标变换到横屏 overlay：(x,y) → (viewW - regionH - y, x)
        val rx1 = (viewW - region.height - region.y).toFloat()
        val ry1 = region.x.toFloat()
        onLog?.invoke("CaptureRegionTransformed: portrait=(${region.x},${region.y},${region.width},${region.height}), " +
                "landscape=(${rx1.toInt()},${ry1.toInt()},${region.width},${region.height}), viewW=$viewW")
        val rx2 = rx1 + region.width
        val ry2 = ry1 + region.height

        // 绘制区域外遮罩
        canvas.drawRect(0f, 0f, viewW.toFloat(), ry1, captureRegionMaskPaint)
        canvas.drawRect(0f, ry2, viewW.toFloat(), viewH.toFloat(), captureRegionMaskPaint)
        canvas.drawRect(0f, ry1, rx1, ry2, captureRegionMaskPaint)
        canvas.drawRect(rx2, ry1, viewW.toFloat(), ry2, captureRegionMaskPaint)

        // 绘制虚线边框
        canvas.drawRect(rx1, ry1, rx2, ry2, captureRegionPaint)

        // 绘制标签
        val labelText = "${region.width}×${region.height}"
        canvas.drawText(labelText, rx1 + 4f, ry1 - 4f, captureRegionLabelPaint)
    }

    /**
     * 绘制旋转后的截屏区域框
     * 将竖屏源坐标变换到旋转后的 canvas 坐标系
     */
    private fun drawCaptureRegionRotated(canvas: Canvas, scaleX: Float, scaleY: Float,
                                          rotation: Int, srcW: Int, srcH: Int,
                                          effectiveW: Int, effectiveH: Int) {
        val region = captureRegion
        // 变换截屏区域的四个角
        val (tx1, ty1) = transformCoord(region.x.toFloat(), region.y.toFloat(), rotation, srcW, srcH)
        val (tx2, ty2) = transformCoord((region.x + region.width).toFloat(), (region.y + region.height).toFloat(), rotation, srcW, srcH)

        val left = minOf(tx1, tx2) * scaleX
        val top = minOf(ty1, ty2) * scaleY
        val right = maxOf(tx1, tx2) * scaleX
        val bottom = maxOf(ty1, ty2) * scaleY

        // 绘制区域外遮罩
        canvas.drawRect(0f, 0f, effectiveW.toFloat(), top, captureRegionMaskPaint)
        canvas.drawRect(0f, bottom, effectiveW.toFloat(), effectiveH.toFloat(), captureRegionMaskPaint)
        canvas.drawRect(0f, top, left, bottom, captureRegionMaskPaint)
        canvas.drawRect(right, top, effectiveW.toFloat(), bottom, captureRegionMaskPaint)

        // 绘制虚线边框
        canvas.drawRect(left, top, right, bottom, captureRegionPaint)

        // 绘制标签
        val labelText = "${region.width}×${region.height}"
        canvas.drawText(labelText, left + 4f, top - 4f, captureRegionLabelPaint)
    }

    /**
     * 绘制状态指示器（右下角小字，确认悬浮窗可见）
     * 当导航栏（手势指示条）可见时，在导航栏上方保持适当边距
     * 当导航栏隐藏时（沉浸模式），紧贴屏幕底部
     */
    private fun drawStatusIndicator(canvas: Canvas, viewW: Int, viewH: Int) {
        val text = "ALOYO | ${detections.size} dets"
        val textWidth = statusPaint.measureText(text)
        val textHeight = statusPaint.fontMetrics.let { it.descent - it.ascent }
        val padding = 8f
        // 导航栏可见时，在导航栏上方留出空间，避免与手势指示条重叠
        // 导航栏隐藏时，紧贴屏幕底部
        val bottomMargin = if (isNavigationBarVisible && navigationBarHeight > 0) {
            navigationBarHeight.toFloat() + 12f
        } else {
            12f
        }
        val x = viewW.toFloat() - textWidth - padding * 2 - 12f
        val y = viewH.toFloat() - textHeight - padding * 2 - bottomMargin

        // 绘制背景
        canvas.drawRect(
            x - padding,
            y - padding,
            viewW.toFloat() - 12f + padding,
            viewH.toFloat() - bottomMargin + padding,
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

        // 如果不显示标签，只绘制框
        if (!config.showLabel) {
            return
        }

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
     * 绘制横屏 overlay 时的检测结果
     * 将竖屏源坐标变换到横屏 canvas 坐标系
     * 变换公式：竖屏 (x, y) → 横屏 (y, x)（当 viewW = srcH 时）
     */
    private fun drawDetectionLandscape(canvas: Canvas, detection: Detection,
                                        viewW: Int, viewH: Int, srcW: Int, srcH: Int,
                                        scaleX: Float, scaleY: Float) {
        // 竖屏坐标变换到横屏 canvas：(x,y) → (y, x)
        // 变换后坐标范围：x: 0-srcH(=viewW), y: 0-srcW(=viewH)
        // 不需要缩放，直接使用
        val x1 = detection.y1
        val y1 = detection.x1
        val x2 = detection.y2
        val y2 = detection.x2

        // 调试日志
        onLog?.invoke("drawDetectionLandscape: portrait=(${detection.x1.toInt()},${detection.y1.toInt()},${detection.x2.toInt()},${detection.y2.toInt()}), " +
                "landscape=(${x1.toInt()},${y1.toInt()},${x2.toInt()},${y2.toInt()})")

        // 确保坐标有序
        val left = minOf(x1, x2)
        val top = minOf(y1, y2)
        val right = maxOf(x1, x2)
        val bottom = maxOf(y1, y2)

        // 绘制边界框
        canvas.drawRect(left, top, right, bottom, boxPaint)

        // 如果不显示标签，只绘制框
        if (!config.showLabel) return

        // 绘制标签背景和文字
        val labelText = if (config.showConfidence) {
            "${detection.label} ${(detection.confidence * 100).toInt()}%"
        } else {
            detection.label
        }

        val textWidth = textPaint.measureText(labelText)
        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        val labelX = left
        val labelY = top - textHeight - 4f

        canvas.drawRect(labelX, labelY, labelX + textWidth + 8f, labelY + textHeight + 4f, textBgPaint)
        canvas.drawText(labelText, labelX + 4f, labelY + textHeight, textPaint)
    }

    /**
     * 在竖屏 canvas 上绘制旋转后的检测框（不使用 canvas rotation）
     * 直接对坐标做变换，避免 canvas rotation 导致位置错开
     *
     * 270° 旋转：(x, y) → canvas (srcH - y, x)
     * 90° 旋转：(x, y) → canvas (srcW - y, x) ... 不对，应该是 (y, srcW - x) 的逆
     *
     * 推导：
     * 设备旋转 270° 顺时针，物理屏幕坐标 (px, py) 对应 canvas (py, srcH - px)
     * 所以检测框 (x1, y1, x2, y2) 映射到 canvas：
     *   left = srcH - max(y1, y2), top = min(x1, x2), right = srcH - min(y1, y2), bottom = max(x1, x2)
     *
     * 设备旋转 90° 顺时针，物理屏幕坐标 (px, py) 对应 canvas (srcW - py, px)
     * 所以检测框 (x1, y1, x2, y2) 映射到 canvas：
     *   left = min(y1, y2), top = srcW - max(x1, x2), right = max(y1, y2), bottom = srcW - min(x1, x2)
     */
    private fun drawDetectionRotatedDirect(canvas: Canvas, detection: Detection, rotation: Int, srcW: Int, srcH: Int) {
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float

        if (rotation == 270) {
            left = srcH.toFloat() - maxOf(detection.y1, detection.y2)
            top = minOf(detection.x1, detection.x2)
            right = srcH.toFloat() - minOf(detection.y1, detection.y2)
            bottom = maxOf(detection.x1, detection.x2)
        } else {
            // rotation == 90
            left = minOf(detection.y1, detection.y2)
            top = srcW.toFloat() - maxOf(detection.x1, detection.x2)
            right = maxOf(detection.y1, detection.y2)
            bottom = srcW.toFloat() - minOf(detection.x1, detection.x2)
        }

        canvas.drawRect(left, top, right, bottom, boxPaint)

        if (!config.showLabel) return

        val labelText = if (config.showConfidence) {
            "${detection.label} ${(detection.confidence * 100).toInt()}%"
        } else {
            detection.label
        }

        val textWidth = textPaint.measureText(labelText)
        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        val labelX = left
        val labelY = top - textHeight - 4f

        canvas.drawRect(labelX, labelY, labelX + textWidth + 8f, labelY + textHeight + 4f, textBgPaint)
        canvas.drawText(labelText, labelX + 4f, labelY + textHeight, textPaint)
    }

    /**
     * 在竖屏 canvas 上绘制旋转后的截屏区域框（不使用 canvas rotation）
     */
    private fun drawCaptureRegionRotatedDirect(canvas: Canvas, rotation: Int, srcW: Int, srcH: Int) {
        val region = captureRegion
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float

        if (rotation == 270) {
            left = (srcH - region.y - region.height).toFloat()
            top = region.x.toFloat()
            right = (srcH - region.y).toFloat()
            bottom = (region.x + region.width).toFloat()
        } else {
            // rotation == 90
            left = region.y.toFloat()
            top = (srcW - region.x - region.width).toFloat()
            right = (region.y + region.height).toFloat()
            bottom = (srcW - region.x).toFloat()
        }

        val viewW = width
        val viewH = height

        // 绘制区域外遮罩
        canvas.drawRect(0f, 0f, viewW.toFloat(), top, captureRegionMaskPaint)
        canvas.drawRect(0f, bottom, viewW.toFloat(), viewH.toFloat(), captureRegionMaskPaint)
        canvas.drawRect(0f, top, left, bottom, captureRegionMaskPaint)
        canvas.drawRect(right, top, viewW.toFloat(), bottom, captureRegionMaskPaint)

        // 绘制虚线边框
        canvas.drawRect(left, top, right, bottom, captureRegionPaint)

        // 绘制标签
        val labelText = "${region.width}×${region.height}"
        canvas.drawText(labelText, left + 4f, top - 4f, captureRegionLabelPaint)
    }

    /**
     * 绘制旋转后的检测结果
     * 将竖屏源坐标变换到旋转后的 canvas 坐标系
     * @param rotation 旋转角度（90 或 270）
     * @param srcW 竖屏源宽度（如 1264）
     * @param srcH 竖屏源高度（如 2780）
     */
    private fun drawDetectionRotated(canvas: Canvas, detection: Detection, scaleX: Float, scaleY: Float,
                                      rotation: Int, srcW: Int, srcH: Int) {
        // 将竖屏源坐标变换到旋转后的 canvas 坐标系
        val (rx1, ry1) = transformCoord(detection.x1, detection.y1, rotation, srcW, srcH)
        val (rx2, ry2) = transformCoord(detection.x2, detection.y2, rotation, srcW, srcH)

        // transformCoord 已经将坐标变换到旋转后的 canvas 坐标系
        // 旋转后的坐标范围：x: 0-srcH, y: 0-srcW（对于 90/270°）
        // 需要缩放到 canvas 的有效绘制区域
        val viewW = width
        val viewH = height
        val effectiveW = if (rotation == 90 || rotation == 270) viewH else viewW
        val effectiveH = if (rotation == 90 || rotation == 270) viewW else viewH

        // 旋转后坐标已经在正确的范围内，只需要缩放到 canvas 尺寸
        val finalScaleX = effectiveW.toFloat() / srcH.toFloat()
        val finalScaleY = effectiveH.toFloat() / srcW.toFloat()

        // 确保坐标有序（旋转后可能反转）
        val left = minOf(rx1, rx2) * finalScaleX
        val top = minOf(ry1, ry2) * finalScaleY
        val right = maxOf(rx1, rx2) * finalScaleX
        val bottom = maxOf(ry1, ry2) * finalScaleY

        canvas.drawRect(left, top, right, bottom, boxPaint)

        if (!config.showLabel) return

        val labelText = if (config.showConfidence) {
            "${detection.label} ${(detection.confidence * 100).toInt()}%"
        } else {
            detection.label
        }

        val textWidth = textPaint.measureText(labelText)
        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        val labelX = left
        val labelY = top - textHeight - 4f

        canvas.drawRect(labelX, labelY, labelX + textWidth + 8f, labelY + textHeight + 4f, textBgPaint)
        canvas.drawText(labelText, labelX + 4f, labelY + textHeight, textPaint)
    }

    /**
     * 将竖屏源坐标变换到旋转后的 canvas 坐标系
     * 90° 顺时针: (x, y) → (srcH - y, x)
     * 270° 顺时针: (x, y) → (y, srcW - x)
     */
    private fun transformCoord(x: Float, y: Float, rotation: Int, srcW: Int, srcH: Int): Pair<Float, Float> {
        return when (rotation) {
            // 90° 显示旋转：canvas 旋转 270° 顺时针抵消
            // 变换：(x, y) → (srcH - y, x)
            90 -> Pair(srcH.toFloat() - y, x)
            // 270° 显示旋转：canvas 旋转 90° 顺时针抵消
            // 变换：(x, y) → (y, srcW - x)
            270 -> Pair(y, srcW.toFloat() - x)
            else -> Pair(x, y)
        }
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
