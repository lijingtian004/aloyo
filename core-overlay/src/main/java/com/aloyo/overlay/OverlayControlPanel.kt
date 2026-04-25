package com.aloyo.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗控制面板
 * 可拖拽的小型控制面板，提供暂停/继续、隐藏等操作
 * 与全屏检测覆盖层分离，独立管理触摸事件
 */
class OverlayControlPanel(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "OverlayControlPanel"
    }

    // 面板内部布局
    private val container: LinearLayout
    private val tvFps: TextView
    private val tvLatency: TextView
    private val btnPause: ImageView
    private val btnHide: ImageView

    // 拖拽相关
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchSlop = 0
    private var startX = 0f
    private var startY = 0f

    // 是否暂停
    @Volatile
    var isPaused: Boolean = false
        private set

    // 回调接口
    var onActionListener: OnActionListener? = null

    /**
     * 控制面板操作回调
     */
    interface OnActionListener {
        // 点击暂停/继续
        fun onPauseToggle(paused: Boolean)
        // 点击隐藏
        fun onHideClicked()
    }

    init {
        // 触摸阈值
        touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        // 创建面板布局
        container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xDD222222.toInt())
        }

        // ALOYO标识
        val tvTitle = TextView(context).apply {
            text = "ALOYO"
            setTextColor(Color.parseColor("#E94560"))
            textSize = 11f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 10, 0)
        }
        container.addView(tvTitle)

        // FPS显示
        tvFps = TextView(context).apply {
            text = "FPS: --"
            setTextColor(Color.GREEN)
            textSize = 10f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 0, 8, 0)
        }

        // 延迟显示
        tvLatency = TextView(context).apply {
            text = "延迟: --ms"
            setTextColor(Color.GREEN)
            textSize = 10f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 0, 12, 0)
        }

        // 暂停按钮
        btnPause = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setPadding(4, 4, 4, 4)
            setOnClickListener {
                isPaused = !isPaused
                updatePauseButton()
                onActionListener?.onPauseToggle(isPaused)
            }
        }

        // 隐藏按钮
        btnHide = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setPadding(4, 4, 4, 4)
            setOnClickListener {
                onActionListener?.onHideClicked()
            }
        }

        container.addView(tvFps)
        container.addView(tvLatency)
        container.addView(btnPause)
        container.addView(btnHide)
        addView(container)
    }

    /**
     * 更新暂停按钮图标
     */
    private fun updatePauseButton() {
        if (isPaused) {
            btnPause.setImageResource(android.R.drawable.ic_media_play)
        } else {
            btnPause.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    /**
     * 更新性能指标显示
     */
    fun updateMetrics(fps: Float, latencyMs: Long) {
        tvFps.text = "FPS: ${"%.1f".format(fps)}"
        tvLatency.text = "延迟: ${latencyMs}ms"
    }

    /**
     * 处理触摸事件，支持拖拽移动
     * 按钮点击由各自的OnClickListener处理
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                lastTouchX = ev.rawX
                lastTouchY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - startX
                val dy = ev.rawY - startY
                // 超过触摸阈值则开始拖拽
                if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    isDragging = true
                }
            }
        }
        return isDragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    updateViewPosition(dx, dy)
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 更新悬浮窗位置
     */
    private fun updateViewPosition(dx: Float, dy: Float) {
        val params = layoutParams as? WindowManager.LayoutParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.updateViewLayout(this, params)
    }
}
