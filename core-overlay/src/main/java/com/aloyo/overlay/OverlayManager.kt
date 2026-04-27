package com.aloyo.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
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
     * 使用getRealMetrics获取真实全屏尺寸（含刘海和导航栏），显式设置窗口大小
     * 确保overlay覆盖完整屏幕，包括刘海区域和导航栏区域
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

        // 获取真实全屏尺寸（含刘海和导航栏）
        val realScreenSize = getRealScreenSize()

        // 使用真实全屏尺寸显式设置窗口大小，而非MATCH_PARENT
        // MATCH_PARENT不包含导航栏区域，导致overlay底部留白
        // FLAG_LAYOUT_NO_LIMITS允许窗口延伸到系统装饰区域（刘海、导航栏）
        overlayLayoutParams = WindowManager.LayoutParams(
            realScreenSize.width,
            realScreenSize.height,
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

        // 检测导航栏状态并通知overlay
        updateNavigationBarInfo()
    }

    /**
     * 获取真实全屏尺寸（包含刘海和导航栏）
     * getRealMetrics返回包含系统装饰的完整屏幕尺寸
     */
    private fun getRealScreenSize(): ScreenSize {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return ScreenSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    /**
     * 获取应用窗口尺寸（不含导航栏）
     * 用于判断导航栏是否可见
     */
    private fun getAppScreenSize(): ScreenSize {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        return ScreenSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    /**
     * 检测导航栏状态并通知overlay
     * 当手势指示条显示时，导航栏占用屏幕底部空间
     * 当手势指示条隐藏时（沉浸模式），导航栏不占用空间
     */
    private fun updateNavigationBarInfo() {
        val realSize = getRealScreenSize()
        val appSize = getAppScreenSize()
        // 导航栏高度 = 真实全屏高度 - 应用窗口高度
        val navBarHeight = realSize.height - appSize.height
        // 导航栏可见：真实高度 > 应用高度（差值即为导航栏高度）
        val isNavBarVisible = navBarHeight > 0

        mainHandler.post {
            overlayView?.setNavigationBarInfo(isNavBarVisible, navBarHeight)
        }

        android.util.Log.i(TAG, "NavigationBar: visible=$isNavBarVisible, height=$navBarHeight, " +
                "realSize=${realSize.width}x${realSize.height}, appSize=${appSize.width}x${appSize.height}")
    }

    /**
     * 屏幕尺寸数据类
     */
    private data class ScreenSize(val width: Int, val height: Int)

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
     * 设置是否显示截屏区域框
     */
    fun setShowCaptureRegion(show: Boolean) {
        mainHandler.post {
            overlayView?.showCaptureRegion = show
            overlayView?.invalidate()
        }
    }

    /**
     * 设置截屏区域（用于绘制截屏范围框）
     */
    fun setCaptureRegion(region: com.aloyo.common.CaptureRegion) {
        mainHandler.post {
            overlayView?.setCaptureRegion(region)
            overlayView?.invalidate()
        }
    }

    /**
     * 刷新导航栏状态
     * 在屏幕旋转、导航栏显示/隐藏变化时调用
     * 同时更新overlay窗口尺寸以匹配当前真实全屏尺寸
     */
    fun refreshNavigationBarState() {
        updateNavigationBarInfo()
        // 更新overlay窗口尺寸以匹配当前真实全屏尺寸
        val realSize = getRealScreenSize()
        val params = overlayLayoutParams
        if (params != null && overlayView != null) {
            if (params.width != realSize.width || params.height != realSize.height) {
                params.width = realSize.width
                params.height = realSize.height
                try {
                    windowManager.updateViewLayout(overlayView, params)
                    android.util.Log.i(TAG, "Overlay size updated to ${realSize.width}x${realSize.height}")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to update overlay size", e)
                }
            }
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
     * 获取当前渲染配置
     */
    fun getConfig(): OverlayConfig {
        return config
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
