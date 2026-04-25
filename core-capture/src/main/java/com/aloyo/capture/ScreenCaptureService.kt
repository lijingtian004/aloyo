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

        // 服务运行状态回调
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
        /**
         * 获取ScreenCaptureService实例
         */
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 显示前台通知
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 获取MediaProjection授权数据
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == -1 || resultData == null) {
            android.util.Log.e(TAG, "Invalid MediaProjection result data")
            stopSelf()
            return START_NOT_STICKY
        }

        // 初始化并启动截屏
        captureManager = CaptureManager(this)
        captureManager?.startCapture(resultCode, resultData)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        captureManager?.stopCapture()
        captureManager = null
        isRunning = false
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
        captureManager?.setFrameCallback(callback)
    }

    /**
     * 设置截屏区域
     */
    fun setCaptureRegion(region: CaptureRegion) {
        captureManager?.setCaptureRegion(region)
    }
}
