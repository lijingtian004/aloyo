package com.aloyo.common

/**
 * 截屏区域数据类
 * 定义截屏的坐标和尺寸
 */
data class CaptureRegion(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    companion object {
        // 预设的常用截屏区域比例
        val FULL_SCREEN = CaptureRegion(0, 0, 0, 0)

        // 创建指定比例的区域（居中）
        fun createRatioRegion(screenWidth: Int, screenHeight: Int, ratioW: Int, ratioH: Int): CaptureRegion {
            val targetRatio = ratioW.toFloat() / ratioH.toFloat()
            val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
            val regionWidth: Int
            val regionHeight: Int
            if (screenRatio > targetRatio) {
                regionHeight = screenHeight
                regionWidth = (screenHeight * targetRatio).toInt()
            } else {
                regionWidth = screenWidth
                regionHeight = (screenWidth / targetRatio).toInt()
            }
            val x = (screenWidth - regionWidth) / 2
            val y = (screenHeight - regionHeight) / 2
            return CaptureRegion(x, y, regionWidth, regionHeight)
        }
    }

    // 判断是否为全屏截屏
    val isFullScreen: Boolean get() = width == 0 && height == 0
}
