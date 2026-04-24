package com.aloyo.common

import android.graphics.Bitmap

/**
 * 截屏源接口
 * 定义截屏采集的统一接口
 */
interface ICaptureSource {

    // 判断截屏是否正在运行
    val isCapturing: Boolean

    // 获取当前截屏区域
    val captureRegion: CaptureRegion

    /**
     * 设置截屏帧回调
     * @param callback 帧回调接口
     */
    fun setFrameCallback(callback: FrameCallback?)

    /**
     * 设置截屏区域
     * @param region 截屏区域
     */
    fun setCaptureRegion(region: CaptureRegion)

    /**
     * 开始截屏
     * @return 是否成功开始
     */
    fun startCapture(): Boolean

    /**
     * 停止截屏
     */
    fun stopCapture()

    /**
     * 帧回调接口
     */
    interface FrameCallback {
        /**
         * 收到新帧
         * @param bitmap 截屏帧图像
         * @param captureTimeMs 截屏耗时（毫秒）
         */
        fun onFrame(bitmap: Bitmap, captureTimeMs: Long)

        /**
         * 截屏出错
         * @param error 错误信息
         */
        fun onError(error: String)
    }
}
