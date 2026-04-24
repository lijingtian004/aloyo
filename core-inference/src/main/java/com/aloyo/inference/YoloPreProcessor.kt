package com.aloyo.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix

/**
 * YOLO模型预处理器
 * 将输入Bitmap转换为模型所需的FloatArray格式
 * 包括缩放、颜色通道转换(RGB)、归一化等操作
 */
class YoloPreProcessor(
    private val targetWidth: Int,
    private val targetHeight: Int
) {
    companion object {
        private const val TAG = "YoloPreProcessor"
    }

    /**
     * 处理输入图像
     * 1. 将Bitmap缩放到模型输入尺寸
     * 2. 将像素值从[0,255]归一化到[0,1]
     * 3. 按RGB通道顺序排列（NCNN默认BGR需注意）
     * @param bitmap 输入图像
     * @return 处理后的FloatArray，大小为 3 * targetWidth * targetHeight
     */
    fun process(bitmap: Bitmap): FloatArray {
        // 缩放Bitmap到模型输入尺寸
        val scaledBitmap = scaleBitmap(bitmap, targetWidth, targetHeight)

        val pixels = IntArray(targetWidth * targetHeight)
        scaledBitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        // NCNN默认使用BGR格式，归一化到[0,1]
        val channelSize = targetWidth * targetHeight
        val inputData = FloatArray(3 * channelSize)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // B通道
            inputData[i] = ((pixel shr 16) and 0xFF) / 255.0f
            // G通道
            inputData[channelSize + i] = ((pixel shr 8) and 0xFF) / 255.0f
            // R通道
            inputData[2 * channelSize + i] = (pixel and 0xFF) / 255.0f
        }

        // 回收缩放后的Bitmap（如果不是原始Bitmap）
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        return inputData
    }

    /**
     * 缩放Bitmap到指定尺寸
     * 使用Letterbox方式保持宽高比，不足部分填充灰色(128)
     */
    private fun scaleBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height

        // 计算缩放比例（取较小值，保持宽高比）
        val scaleX = targetWidth.toFloat() / srcWidth
        val scaleY = targetHeight.toFloat() / srcHeight
        val scale = minOf(scaleX, scaleY)

        val scaledWidth = (srcWidth * scale).toInt()
        val scaledHeight = (srcHeight * scale).toInt()

        // 创建目标尺寸的Bitmap，填充灰色背景
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(0xFF808080.toInt()) // 灰色填充

        // 绘制缩放后的图像到中心位置
        val dx = (targetWidth - scaledWidth) / 2f
        val dy = (targetHeight - scaledHeight) / 2f

        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)

        canvas.drawBitmap(bitmap, matrix, null)

        return result
    }

    /**
     * 计算从模型坐标映射回原图坐标的缩放因子
     * 用于后处理中将检测框坐标转换回原图尺寸
     */
    fun getScaleFactors(srcWidth: Int, srcHeight: Int): ScaleFactors {
        val scaleX = targetWidth.toFloat() / srcWidth
        val scaleY = targetHeight.toFloat() / srcHeight
        val scale = minOf(scaleX, scaleY)

        val scaledWidth = (srcWidth * scale).toInt()
        val scaledHeight = (srcHeight * scale).toInt()

        val padX = (targetWidth - scaledWidth) / 2f
        val padY = (targetHeight - scaledHeight) / 2f

        return ScaleFactors(
            scale = scale,
            padX = padX,
            padY = padY
        )
    }

    /**
     * 缩放因子数据类
     */
    data class ScaleFactors(
        val scale: Float,
        val padX: Float,
        val padY: Float
    )
}
