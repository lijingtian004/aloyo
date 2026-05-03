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

    // 横屏锁定：设为 true 后，overlay 始终保持横屏尺寸，不会被重建回竖屏
    // 用于 OnePlus 关闭自动旋转场景：系统不旋转 overlay，必须用横屏 overlay 匹配横屏 bitmap
    @Volatile
    var forceLandscapeOnce: Boolean = false

    // 上次重建时间（防抖，避免频繁重建导致 ANR）
    private var lastRecreateTimeMs: Long = 0
    private val RECREATE_DEBOUNCE_MS = 2000L

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
     *
     * @param forcedWidth 强制指定的窗口宽度（可选，用于方向变化时确保使用正确尺寸）
     * @param forcedHeight 强制指定的窗口高度（可选，用于方向变化时确保使用正确尺寸）
     *                    当外部已经获取到正确的屏幕尺寸时，传入此参数可避免WindowManager返回旧尺寸的问题
     */
    private fun showDetectionOverlay(forcedWidth: Int = 0, forcedHeight: Int = 0) {
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
        // 如果外部传入了强制尺寸，优先使用外部传入的值
        // 原因：在某些设备上（如OnePlus/Android 15），当应用未随方向旋转时，
        // WindowManager.currentWindowMetrics 可能返回旧方向的尺寸
        val realScreenSize = if (forcedWidth > 0 && forcedHeight > 0) {
            android.util.Log.i(TAG, "Using forced screen size: ${forcedWidth}x${forcedHeight}")
            ScreenSize(forcedWidth, forcedHeight)
        } else {
            getRealScreenSize()
        }

        // 使用真实全屏尺寸显式设置窗口大小，而非MATCH_PARENT
        // MATCH_PARENT不包含导航栏区域，导致overlay底部留白
        // FLAG_LAYOUT_NO_LIMITS允许窗口延伸到系统装饰区域（刘海、导航栏）
        // 始终使用竖屏尺寸（短边 x 长边），系统会自动旋转显示
        val portraitW = minOf(realScreenSize.width, realScreenSize.height)
        val portraitH = maxOf(realScreenSize.width, realScreenSize.height)
        overlayLayoutParams = WindowManager.LayoutParams(
            portraitW,
            portraitH,
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
     * 使用WindowManager获取当前Display对象，这是获取屏幕旋转后最新尺寸最可靠的方法
     */
    private fun getRealScreenSize(): ScreenSize {
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 WindowManager.getCurrentWindowMetrics()
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            return ScreenSize(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            return ScreenSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
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
     * 横屏模式下自动调整初始位置，避免遮挡画面中央
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

        // 根据当前屏幕方向计算控制面板初始位置
        // 横屏时放在左上角，竖屏时也放在左上角但留出状态栏空间
        val realSize = getRealScreenSize()
        val isLandscape = realSize.width > realSize.height
        val initialX = 16
        val initialY = if (isLandscape) 16 else 100

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
            x = initialX
            y = initialY
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
            forceLandscapeOnce = false
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
     * 设置显示旋转角度
     * 用于在横屏时旋转overlay的canvas坐标系，使绘制内容匹配实际显示方向
     * @param degrees 旋转角度（0, 90, 180, 270）
     */
    fun setDisplayRotation(degrees: Int) {
        mainHandler.post {
            overlayView?.setDisplayRotation(degrees)
        }
    }

    /**
     * 刷新导航栏状态
     * 在屏幕旋转、导航栏显示/隐藏变化时调用
     * 同时更新overlay窗口尺寸以匹配当前真实全屏尺寸
     *
     * 重要：屏幕旋转时（宽高互换），必须重新创建overlay窗口，
     * 因为updateViewLayout无法正确处理方向变化导致的尺寸翻转。
     *
     * @param forcedWidth 强制指定的窗口宽度（可选，当外部已获取到正确尺寸时传入）
     * @param forcedHeight 强制指定的窗口高度（可选，当外部已获取到正确尺寸时传入）
     */
    fun refreshNavigationBarState(forcedWidth: Int = 0, forcedHeight: Int = 0) {
        updateNavigationBarInfo()

        // 优先使用外部传入的强制尺寸，避免WindowManager返回旧尺寸
        val realSize = if (forcedWidth > 0 && forcedHeight > 0) {
            ScreenSize(forcedWidth, forcedHeight)
        } else {
            getRealScreenSize()
        }
        val params = overlayLayoutParams

        if (params != null && overlayView != null) {
            // overlay 始终保持竖屏尺寸（1264x2780），不因方向变化重建
            // 横屏显示通过 canvas rotation 实现
            // 只处理非方向性的尺寸微调（如导航栏显示/隐藏）
            if (params.width != realSize.width && params.height != realSize.height) {
                // 两个维度都变了才更新（排除方向变化的情况）
                // 必须在主线程执行 windowManager.updateViewLayout
                mainHandler.post {
                    params.width = realSize.width
                    params.height = realSize.height
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to update overlay size", e)
                    }
                }
            }
        }

        // 同步更新控制面板位置，确保横竖屏切换后控制面板在合适位置
        mainHandler.post { updateControlPanelPositionForOrientation() }
    }

    /**
     * 根据当前屏幕方向更新控制面板位置
     * 横屏时确保控制面板不会遮挡画面中央区域
     */
    private fun updateControlPanelPositionForOrientation() {
        val cpParams = controlLayoutParams
        val cpView = controlPanel
        if (cpParams == null || cpView == null) return

        val realSize = getRealScreenSize()
        val isLandscape = realSize.width > realSize.height

        // 横屏时如果控制面板在屏幕中央区域，将其移到左上角
        if (isLandscape) {
            val centerX = realSize.width / 2
            val centerY = realSize.height / 2
            // 如果控制面板位于屏幕中央30%区域内，将其重置到左上角
            if (cpParams.x > centerX - realSize.width * 0.15 && cpParams.x < centerX + realSize.width * 0.15 &&
                cpParams.y > centerY - realSize.height * 0.15 && cpParams.y < centerY + realSize.height * 0.15
            ) {
                cpParams.x = 16
                cpParams.y = 16
                try {
                    windowManager.updateViewLayout(cpView, cpParams)
                    android.util.Log.i(TAG, "Control panel repositioned to top-left for landscape")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to update control panel position", e)
                }
            }
        }
    }

    /**
     * 重新创建检测覆盖层窗口
     * 屏幕旋转时必须重新创建，因为updateViewLayout无法正确处理方向变化
     *
     * @param forcedWidth 强制指定的窗口宽度（可选）
     * @param forcedHeight 强制指定的窗口高度（可选）
     */
    private fun recreateDetectionOverlay(forcedWidth: Int = 0, forcedHeight: Int = 0) {
        val oldView = overlayView

        // 清除旧引用，确保showDetectionOverlay创建全新的窗口
        overlayView = null
        overlayLayoutParams = null

        // 移除旧窗口
        if (oldView != null) {
            try {
                windowManager.removeView(oldView)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error removing old overlay", e)
            }
        }

        // 创建新窗口（使用传入的强制尺寸或自行查询当前屏幕尺寸）
        showDetectionOverlay(forcedWidth, forcedHeight)

        // 重新创建控制面板，确保其布局参数也使用新的屏幕尺寸
        recreateControlPanel()

        android.util.Log.i(TAG, "Detection overlay recreated")
    }

    /**
     * 公开方法：强制重新创建overlay窗口
     * 供外部调用（如MainActivity检测到方向变化时）
     *
     * @param forcedWidth 强制指定的窗口宽度（可选，当外部已获取到正确尺寸时传入）
     * @param forcedHeight 强制指定的窗口高度（可选，当外部已获取到正确尺寸时传入）
     *                    传入强制尺寸可避免WindowManager在方向变化后返回旧尺寸的问题
     */
    fun forceRecreateOverlay(forcedWidth: Int = 0, forcedHeight: Int = 0) {
        if (!isShowing) {
            android.util.Log.w(TAG, "Cannot recreate overlay: not showing")
            return
        }
        android.util.Log.i(TAG, "Force recreating overlay, forcedSize=${if (forcedWidth > 0) "${forcedWidth}x${forcedHeight}" else "auto"}")
        recreateDetectionOverlay(forcedWidth, forcedHeight)
    }

    /**
     * 获取当前overlay布局参数
     * 供外部调用检查overlay窗口尺寸
     */
    fun getOverlayLayoutParams(): WindowManager.LayoutParams? {
        return overlayLayoutParams
    }

    /**
     * 重新创建控制面板
     * 屏幕旋转后控制面板需要更新位置以适应新的屏幕方向
     */
    private fun recreateControlPanel() {
        val oldPanel = controlPanel

        // 清除旧引用
        controlPanel = null
        controlLayoutParams = null

        // 移除旧窗口
        if (oldPanel != null) {
            try {
                windowManager.removeView(oldPanel)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error removing old control panel", e)
            }
        }

        // 创建新控制面板（使用当前屏幕方向计算初始位置）
        showControlPanel()

        android.util.Log.i(TAG, "Control panel recreated")
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
     * 直接更新 overlay 窗口尺寸（不依赖 getRealScreenSize）
     * 用于 OnePlus 关闭自动旋转场景，传感器检测到横屏后直接设置横屏尺寸
     */
    fun updateOverlaySize(newWidth: Int, newHeight: Int) {
        val params = overlayLayoutParams ?: return
        if (params.width == newWidth && params.height == newHeight) return

        android.util.Log.i(TAG, "updateOverlaySize: ${params.width}x${params.height} -> ${newWidth}x${newHeight}")

        // 必须在主线程执行 windowManager.updateViewLayout
        mainHandler.post {
            params.width = portraitW
            params.height = portraitH
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "updateOverlaySize failed", e)
            }

            updateNavigationBarInfo()
        }
    }

    /**
     * 当前 overlay 窗口是否为横屏尺寸（width > height）
     * 用于判断是否需要 canvas rotation 和坐标变换
     */
    fun isOverlayLandscape(): Boolean {
        val params = overlayLayoutParams ?: return false
        return params.width > params.height
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
