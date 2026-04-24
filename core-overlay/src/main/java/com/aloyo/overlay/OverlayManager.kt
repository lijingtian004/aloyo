package com.aloyo.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.aloyo.common.Detection
import com.aloyo.common.IOverlayRenderer
import com.aloyo.common.OverlayConfig
import com.aloyo.common.PerformanceMetrics

/**
 * 悬浮窗管理器
 * 负责悬浮窗的创建、显示、隐藏和更新
 * 管理WindowManager.LayoutParams和悬浮窗生命周期
 */
class OverlayManager(private val context: Context) : IOverlayRenderer {

    companion object {
        private const val TAG = "OverlayManager"
    }

    // WindowManager实例
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 悬浮窗视图
    private var overlayView: DetectionOverlayView? = null

    // 悬浮窗布局参数
    private var layoutParams: WindowManager.LayoutParams? = null

    // 渲染配置
    private var config: OverlayConfig = OverlayConfig()

    // 显示状态
    @Volatile
    override var isShowing: Boolean = false
        private set

    /**
     * 创建悬浮窗
     * 需要在UI线程调用
     */
    override fun show() {
        if (isShowing) {
            android.util.Log.w(TAG, "Overlay already showing")
            return
        }

        try {
            // 创建悬浮窗视图
            overlayView = DetectionOverlayView(context).apply {
                setOverlayConfig(config)
            }

            // 设置布局参数
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val displayMetrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)

            layoutParams = WindowManager.LayoutParams(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            // 添加悬浮窗
            windowManager.addView(overlayView, layoutParams)
            isShowing = true

            android.util.Log.i(TAG, "Overlay shown")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error showing overlay", e)
        }
    }

    /**
     * 隐藏悬浮窗
     */
    override fun hide() {
        if (!isShowing) return

        try {
            overlayView?.let {
                windowManager.removeView(it)
            }
            overlayView = null
            layoutParams = null
            isShowing = false

            android.util.Log.i(TAG, "Overlay hidden")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error hiding overlay", e)
        }
    }

    /**
     * 更新检测结果
     */
    override fun updateDetections(detections: List<Detection>, metrics: PerformanceMetrics) {
        overlayView?.updateDetections(detections, metrics)
    }

    /**
     * 设置渲染配置
     */
    override fun setConfig(config: OverlayConfig) {
        this.config = config
        overlayView?.setOverlayConfig(config)
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
