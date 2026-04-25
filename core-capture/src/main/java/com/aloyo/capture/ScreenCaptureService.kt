package com.aloyo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aloyo.common.CaptureRegion
import com.aloyo.common.ICaptureSource

/**
 * 截屏前台服务
 * MediaProjection需要在前台服务中运行
 * 通过通知栏显示服务运行状态
 * 支持绑定，允许外部获取CaptureManager设置帧回调
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "aloyo_capture_channel"
        private const val CHANNEL_NAME = "ALOYO截屏服务"
        private const val NOTIFICATION_ID = 1001

        // Intent额外参数键
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // 使用0作为无效值的标记（不能用-1，因为RESULT_OK就是-1）
        private const val INVALID_RESULT_CODE = 0

        // 服务运行状态
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 启动截屏服务
         * @param context 上下文
         * @param resultCode MediaProjection授权结果码
         * @param resultData MediaProjection授权Intent数据
         */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        /**
         * 停止截屏服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
        }
    }

    // 截屏管理器实例
    private var captureManager: CaptureManager? = null

    // 服务绑定Binder
    private val binder = CaptureServiceBinder()

    /**
     * 服务绑定Binder
     * 允许外部获取CaptureManager并设置帧回调
     */
    inner class CaptureServiceBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        android.util.Log.i(TAG, "ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            android.util.Log.e(TAG, "onStartCommand: intent is null")
            stopSelf()
            return START_NOT_STICKY
        }

        // 显示前台通知
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 获取MediaProjection授权数据
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, INVALID_RESULT_CODE)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        android.util.Log.i(TAG, "onStartCommand: resultCode=$resultCode, resultData=${resultData != null}")

        // 注意：不能用resultCode == -1判断失败，因为RESULT_OK就是-1
        // 只有resultCode为默认值0（从未设置）且resultData为null时才是真正的失败
        if (resultCode == INVALID_RESULT_CODE && resultData == null) {
            android.util.Log.e(TAG, "Invalid MediaProjection result data: both resultCode and resultData are invalid")
            stopSelf()
            return START_NOT_STICKY
        }

        if (resultData == null) {
            android.util.Log.e(TAG, "MediaProjection resultData is null")
            stopSelf()
            return START_NOT_STICKY
        }

        // 初始化并启动截屏
        captureManager = CaptureManager(this)
        val captureStarted = captureManager?.startCapture(resultCode, resultData) ?: false
        if (!captureStarted) {
            android.util.Log.e(TAG, "CaptureManager.startCapture() failed")
            stopSelf()
            return START_NOT_STICKY
        }

        android.util.Log.i(TAG, "Screen capture service started successfully")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        android.util.Log.i(TAG, "onBind called")
        return binder
    }

    override fun onDestroy() {
        captureManager?.stopCapture()
        captureManager = null
        isRunning = false
        android.util.Log.i(TAG, "ScreenCaptureService destroyed")
        super.onDestroy()
    }

    /**
     * 创建通知渠道（Android 8.0+必需）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ALOYO截屏服务运行通知"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALOYO")
            .setContentText("截屏推理服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * 设置截屏帧回调
     */
    fun setFrameCallback(callback: ICaptureSource.FrameCallback?) {
        android.util.Log.i(TAG, "setFrameCallback: captureManager=${captureManager != null}, callback=${callback != null}")
        captureManager?.setFrameCallback(callback)
    }

    /**
     * 设置截屏区域
     */
    fun setCaptureRegion(region: CaptureRegion) {
        captureManager?.setCaptureRegion(region)
    }
}
