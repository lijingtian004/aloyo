package com.aloyo.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.aloyo.common.Detection
import com.aloyo.common.IOverlayRenderer
import com.aloyo.common.OverlayConfig
import com.aloyo.common.PerformanceMetrics

/**
 * 悬浮窗管理器
 * 使用双悬浮窗方案：
 * 1. 全屏检测覆盖层（FLAG_NOT_TOUCHABLE）：绘制检测框和标签，触摸穿透
 * 2. 可拖拽控制面板（可交互）：显示性能指标、暂停/继续、隐藏按钮
 */
class OverlayManager(private val context: Context) : IOverlayRenderer {

    companion object {
        private const val TAG = "OverlayManager"
    }

    // WindowManager实例
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 主线程Handler，用于从后台线程更新UI
    private val mainHandler = Handler(Looper.getMainLooper())

    // 全屏检测覆盖层（不可触摸，纯显示）
    private var overlayView: DetectionOverlayView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    // 可拖拽控制面板（可交互）
    private var controlPanel: OverlayControlPanel? = null
    private var controlLayoutParams: WindowManager.LayoutParams? = null

    // 渲染配置
    private var config: OverlayConfig = OverlayConfig()

    // 显示状态
    @Volatile
    override var isShowing: Boolean = false
        private set

    // 暂停状态
    @Volatile
    var isPaused: Boolean = false
        private set

    // 暂停回调
    var onPauseToggle: ((paused: Boolean) -> Unit)? = null

    // 日志回调（用于将诊断日志写入应用日志文件）
    var onLog: ((String) -> Unit)? = null

    /**
     * 创建悬浮窗
     * 同时创建全屏检测覆盖层和可拖拽控制面板
     */
    override fun show() {
        if (isShowing) {
            android.util.Log.w(TAG, "Overlay already showing")
            return
        }

        try {
            showDetectionOverlay()
            showControlPanel()
            isShowing = true
            android.util.Log.i(TAG, "Overlay shown (detection + control panel)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error showing overlay", e)
        }
    }

    /**
     * 创建全屏检测覆盖层
     * FLAG_NOT_TOUCHABLE：触摸穿透到下层应用
     */
    private fun showDetectionOverlay() {
        overlayView = DetectionOverlayView(context).apply {
            setOverlayConfig(config)
            onLog = { msg -> this@OverlayManager.onLog?.invoke(msg) }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        overlayLayoutParams = WindowManager.LayoutParams(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            type,
            // 全屏检测层：不可触摸，让触摸穿透到下层应用
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(overlayView, overlayLayoutParams)
    }

    /**
     * 创建可拖拽控制面板
     * 可交互：支持拖拽移动、暂停/继续、隐藏操作
     */
    private fun showControlPanel() {
        controlPanel = OverlayControlPanel(context).apply {
            onActionListener = object : OverlayControlPanel.OnActionListener {
                override fun onPauseToggle(paused: Boolean) {
                    this@OverlayManager.isPaused = paused
                    this@OverlayManager.onPauseToggle?.invoke(paused)
                }

                override fun onHideClicked() {
                    hide()
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        controlLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 控制面板：可交互，但不阻挡其他区域触摸
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        windowManager.addView(controlPanel, controlLayoutParams)
    }

    /**
     * 隐藏悬浮窗
     */
    override fun hide() {
        if (!isShowing) return

        try {
            overlayView?.let { windowManager.removeView(it) }
            overlayView = null
            overlayLayoutParams = null

            controlPanel?.let { windowManager.removeView(it) }
            controlPanel = null
            controlLayoutParams = null

            isShowing = false
            isPaused = false
            android.util.Log.i(TAG, "Overlay hidden")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error hiding overlay", e)
        }
    }

    /**
     * 更新检测结果
     * 同时更新检测覆盖层和控制面板的性能指标
     * 注意：此方法可能从后台线程调用，UI更新必须post到主线程
     */
    override fun updateDetections(detections: List<Detection>, metrics: PerformanceMetrics) {
        // 从后台线程调用时，post到主线程执行UI更新
        mainHandler.post {
            overlayView?.updateDetections(detections, metrics)
            controlPanel?.updateMetrics(metrics.fps, metrics.inferenceLatencyMs)
        }
    }

    /**
     * 设置原图尺寸（用于坐标映射）
     * 截屏图像的分辨率可能与屏幕分辨率不同
     */
    fun setSourceSize(srcWidth: Int, srcHeight: Int) {
        mainHandler.post {
            overlayView?.setSourceSize(srcWidth, srcHeight)
        }
    }

    /**
     * 设置渲染配置
     */
    override fun setConfig(config: OverlayConfig) {
        this.config = config
        mainHandler.post {
            overlayView?.setOverlayConfig(config)
        }
    }

    /**
     * 检查是否有悬浮窗权限
     */
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        hide()
    }
}
