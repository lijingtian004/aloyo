package com.aloyo.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.aloyo.common.CaptureRegion
import com.aloyo.common.ICaptureSource

/**
 * 截屏管理器
 * 管理MediaProjection、VirtualDisplay和ImageReader
 * 实现屏幕画面的实时采集
 */
class CaptureManager(private val context: Context) : ICaptureSource {

    companion object {
        private const val TAG = "CaptureManager"
        // 截屏帧率目标
        private const val TARGET_FPS = 30
        // VirtualDisplay名称
        private const val VIRTUAL_DISPLAY_NAME = "ALOYO_Capture"
    }

    // 截屏状态
    @Volatile
    override var isCapturing: Boolean = false
        private set

    // 当前截屏区域
    @Volatile
    override var captureRegion: CaptureRegion = CaptureRegion.FULL_SCREEN
        private set

    // MediaProjection相关
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 帧回调
    private var frameCallback: ICaptureSource.FrameCallback? = null

    // 后台处理线程
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // 屏幕尺寸
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    // 上一帧时间戳，用于计算截屏延迟
    private var lastFrameTimeMs: Long = 0

    init {
        initScreenMetrics()
    }

    /**
     * 初始化屏幕尺寸信息
     */
    private fun initScreenMetrics() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi
    }

    /**
     * 启动截屏
     * @param resultCode MediaProjection授权结果码
     * @param resultData MediaProjection授权Intent数据
     */
    fun startCapture(resultCode: Int, resultData: Intent): Boolean {
        if (isCapturing) {
            android.util.Log.w(TAG, "Capture already running")
            return false
        }

        try {
            // 获取MediaProjection
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                android.util.Log.e(TAG, "Failed to get MediaProjection")
                return false
            }

            // 注册MediaProjection回调
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    android.util.Log.i(TAG, "MediaProjection stopped")
                    stopCapture()
                }
            }, null)

            // 初始化后台处理线程
            handlerThread = HandlerThread("ALOYO_CaptureThread").also { it.start() }
            handler = Handler(handlerThread!!.looper)

            // 创建ImageReader和VirtualDisplay
            setupVirtualDisplay()

            isCapturing = true
            android.util.Log.i(TAG, "Screen capture started: ${screenWidth}x${screenHeight}")
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error starting capture", e)
            stopCapture()
            return false
        }
    }

    /**
     * 设置VirtualDisplay和ImageReader
     */
    private fun setupVirtualDisplay() {
        val region = if (captureRegion.isFullScreen) {
            CaptureRegion(0, 0, screenWidth, screenHeight)
        } else {
            captureRegion
        }

        // 创建ImageReader
        imageReader = ImageReader.newInstance(
            region.width,
            region.height,
            PixelFormat.RGBA_8888,
            2
        )

        // 设置ImageReader回调
        imageReader?.setOnImageAvailableListener({ reader ->
            processImage(reader)
        }, handler)

        // 创建VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            region.width,
            region.height,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    /**
     * 处理ImageReader中的新帧
     */
    private fun processImage(reader: ImageReader) {
        val captureStartTime = System.currentTimeMillis()
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            // 将Image转换为Bitmap
            val bitmap = imageToBitmap(image)
            if (bitmap != null) {
                val captureTimeMs = System.currentTimeMillis() - captureStartTime
                frameCallback?.onFrame(bitmap, captureTimeMs)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error processing captured image", e)
            frameCallback?.onError(e.message ?: "Image processing error")
        } finally {
            image?.close()
        }
    }

    /**
     * 将Image转换为Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        // 计算行填充
        val rowPadding = rowStride - pixelStride * width

        // 创建Bitmap
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 如果有行填充，裁剪到正确尺寸
        return if (rowPadding == 0) {
            bitmap
        } else {
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            croppedBitmap
        }
    }

    /**
     * 停止截屏（内部实现）
     */
    private fun internalStopCapture() {
        isCapturing = false

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        android.util.Log.i(TAG, "Screen capture stopped")
    }

    override fun setFrameCallback(callback: ICaptureSource.FrameCallback?) {
        frameCallback = callback
    }

    override fun setCaptureRegion(region: CaptureRegion) {
        captureRegion = region
        // 如果正在截屏，需要重新设置VirtualDisplay
        if (isCapturing) {
            virtualDisplay?.release()
            imageReader?.close()
            setupVirtualDisplay()
        }
    }

    override fun startCapture(): Boolean {
        // 此方法由Service调用startCapture(resultCode, resultData)
        // 直接调用此方法无法获取MediaProjection
        android.util.Log.w(TAG, "Use startCapture(resultCode, resultData) instead")
        return false
    }

    override fun stopCapture() {
        internalStopCapture()
    }
}
