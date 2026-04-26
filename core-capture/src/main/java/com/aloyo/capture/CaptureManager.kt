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

    // 帧计数（用于诊断日志，每隔一段时间打印一次）
    private var frameCount = 0
    private var lastDiagnosticTime = 0L

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
        android.util.Log.i(TAG, "Screen metrics: ${screenWidth}x${screenHeight} @${screenDensity}dpi")
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
                android.util.Log.e(TAG, "Failed to get MediaProjection - resultCode=$resultCode")
                return false
            }

            android.util.Log.i(TAG, "MediaProjection obtained successfully")

            // 注册MediaProjection回调
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    android.util.Log.w(TAG, "MediaProjection stopped unexpectedly")
                    stopCapture()
                }
            }, null)

            // 初始化后台处理线程
            handlerThread = HandlerThread("ALOYO_CaptureThread").also { it.start() }
            handler = Handler(handlerThread!!.looper)

            // 创建ImageReader和VirtualDisplay
            setupVirtualDisplay()

            isCapturing = true
            lastDiagnosticTime = System.currentTimeMillis()
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
        // 始终以全屏尺寸创建VirtualDisplay，确保截取完整屏幕内容
        // 非全屏区域通过processImage中的Bitmap裁剪实现
        val captureWidth = screenWidth
        val captureHeight = screenHeight

        android.util.Log.i(TAG, "Setting up VirtualDisplay: ${captureWidth}x${captureHeight}, region=${captureRegion}")

        // 创建ImageReader
        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // 设置ImageReader回调
        imageReader?.setOnImageAvailableListener({ reader ->
            processImage(reader)
        }, handler)

        android.util.Log.i(TAG, "ImageReader created: ${captureWidth}x${captureHeight}, surface=${imageReader?.surface != null}")

        // 创建VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            captureWidth,
            captureHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    android.util.Log.w(TAG, "VirtualDisplay paused")
                }

                override fun onResumed() {
                    android.util.Log.i(TAG, "VirtualDisplay resumed")
                }

                override fun onStopped() {
                    android.util.Log.w(TAG, "VirtualDisplay stopped")
                }
            },
            handler
        )

        if (virtualDisplay == null) {
            android.util.Log.e(TAG, "Failed to create VirtualDisplay!")
        } else {
            android.util.Log.i(TAG, "VirtualDisplay created successfully")
        }
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
                // 如果设置了截屏区域，裁剪Bitmap到指定区域
                val finalBitmap = if (!captureRegion.isFullScreen) {
                    cropBitmap(bitmap, captureRegion)
                } else {
                    bitmap
                }

                val captureTimeMs = System.currentTimeMillis() - captureStartTime
                val callback = frameCallback
                if (callback != null) {
                    callback.onFrame(finalBitmap, captureTimeMs)
                } else {
                    // 没有回调，回收Bitmap
                    finalBitmap.recycle()
                    if (finalBitmap !== bitmap) bitmap.recycle()
                }

                // 诊断日志：每3秒打印一次帧统计
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastDiagnosticTime >= 3000) {
                    val elapsed = now - lastDiagnosticTime
                    val fps = frameCount * 1000f / elapsed
                    android.util.Log.i(TAG, "Capture stats: ${frameCount} frames in ${elapsed}ms (${String.format("%.1f", fps)} fps), callback=${callback != null}")
                    frameCount = 0
                    lastDiagnosticTime = now
                }
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
     * 裁剪Bitmap到指定截屏区域
     * 确保裁剪区域在Bitmap范围内
     */
    private fun cropBitmap(bitmap: Bitmap, region: CaptureRegion): Bitmap {
        // 确保裁剪区域在Bitmap范围内
        val x = region.x.coerceIn(0, bitmap.width - 1)
        val y = region.y.coerceIn(0, bitmap.height - 1)
        val w = region.width.coerceIn(1, bitmap.width - x)
        val h = region.height.coerceIn(1, bitmap.height - y)

        return Bitmap.createBitmap(bitmap, x, y, w, h)
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
        android.util.Log.i(TAG, "setFrameCallback: callback=${callback != null}, isCapturing=$isCapturing")
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
