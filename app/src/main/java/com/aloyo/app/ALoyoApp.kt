package com.aloyo.app

import android.app.Application
import com.aloyo.common.ILogger
import com.aloyo.common.LogLevel
import com.aloyo.logger.ALoyoLogger
import java.io.File

/**
 * ALOYO应用入口
 * 初始化全局组件：日志系统、模型管理器等
 */
class ALoyoApp : Application() {

    companion object {
        private const val TAG = "ALoyoApp"

        @Volatile
        private var instance: ALoyoApp? = null

        fun getInstance(): ALoyoApp = instance!!
    }

    // 全局日志器
    lateinit var logger: ALoyoLogger
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化日志系统
        val logDir = File(getExternalFilesDir(null), "logs")
        logger = ALoyoLogger.getInstance(logDir, LogLevel.DEBUG)

        logger.info(TAG, "ALOYO Application starting...")
        logger.info(TAG, "App version: ${BuildConfig.VERSION_NAME}")
        logger.info(TAG, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        logger.info(TAG, "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")

        logger.info(TAG, "ALOYO Application initialized")
    }

    override fun onTerminate() {
        logger.info(TAG, "ALOYO Application terminating")
        logger.shutdown()
        super.onTerminate()
    }
}
