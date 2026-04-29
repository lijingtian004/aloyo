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

    // 屏幕尺寸（实时更新，旋转后宽高互换）
    @Volatile private var screenWidth: Int = 0
    @Volatile private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    // VirtualDisplay创建时的方向（仅用于日志记录）
    // 0=竖屏(portrait), 1=横屏(landscape)
    @Volatile private var displayOrientation: Int = 0

    // 当前屏幕尺寸的公开访问器（旋转后会更新）
    val currentScreenWidth: Int get() = screenWidth
    val currentScreenHeight: Int get() = screenHeight

    // 最新截屏bitmap的尺寸（反映物理屏幕真实方向）
    // 比WindowManager/Display更可靠，因为来自MediaProjection实际捕获的图像
    @Volatile var lastBitmapWidth: Int = 0
        private set
    @Volatile var lastBitmapHeight: Int = 0
        private set

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
        displayOrientation = if (screenWidth > screenHeight) 1 else 0
        android.util.Log.i(TAG, "Screen metrics: ${screenWidth}x${screenHeight} @${screenDensity}dpi, orientation=${if (displayOrientation==1) "landscape" else "portrait"}")
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
     *
     * 重要：VirtualDisplay使用AUTO_MIRROR标志，旋转后bitmap会自动适配新方向
     * 不需要手动旋转bitmap！手动旋转会导致坐标系错乱。
     *
     * 横屏适配原理：
     * 1. VirtualDisplay的AUTO_MIRROR会自动将主屏幕内容（包括旋转后的）镜像到VirtualDisplay
     * 2. 横屏时，bitmap的宽高 = 横屏的宽高（如1920x1080）
     * 3. 截屏区域坐标也是基于横屏坐标系计算的
     * 4. 推理在横屏bitmap上进行，检测框坐标自然就是横屏坐标
     * 5. overlay使用横屏屏幕尺寸，坐标映射一致
     */
    private fun processImage(reader: ImageReader) {
        val captureStartTime = System.currentTimeMillis()
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            // 检查截屏是否已停止（避免在停止过程中处理帧）
            if (!isCapturing) {
                image.close()
                return
            }

            // 将Image转换为Bitmap
            var bitmap = imageToBitmap(image)
            if (bitmap != null) {
                // 检测bitmap方向是否与当前屏幕方向匹配
                // VirtualDisplay创建时尺寸固定，旋转后bitmap仍然是旧方向
                // 需要手动旋转bitmap以匹配当前屏幕方向
                val isLandscape = displayOrientation == 1
                val bitmapIsLandscape = bitmap.width > bitmap.height

                if (isLandscape != bitmapIsLandscape) {
                    // 方向不匹配，旋转bitmap
                    // 竖屏bitmap(1264x2780) → 旋转90° → 横屏bitmap(2780x1264)
                    val rotationDegrees = if (isLandscape) 90f else -90f
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotationDegrees)
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    bitmap.recycle()
                    bitmap = rotatedBitmap

                    if (frameCount == 0) {
                        android.util.Log.i(TAG, "Rotated bitmap to match screen orientation: ${bitmap.width}x${bitmap.height}")
                    }
                }

                // 记录全屏bitmap尺寸（裁剪前）
                lastBitmapWidth = bitmap.width
                lastBitmapHeight = bitmap.height

                // 诊断：记录bitmap尺寸和当前屏幕尺寸
                if (frameCount == 0) {
                    android.util.Log.i(TAG, "processImage: bitmap=${bitmap.width}x${bitmap.height}, screen=${screenWidth}x${screenHeight}")
                }

                // 如果设置了截屏区域，裁剪Bitmap到指定区域
                // 截屏区域坐标是基于当前屏幕方向的，bitmap也已旋转到当前方向，直接裁剪即可
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
     * 保留方法但不再使用：AUTO_MIRROR已自动处理旋转
     * 当VirtualDisplay的bitmap方向与当前屏幕方向不匹配时（旋转后），
     * 需要将bitmap旋转90度以匹配当前屏幕方向
     *
     * @deprecated AUTO_MIRROR标志已自动处理旋转，此方法不再调用
     */
    @Deprecated("AUTO_MIRROR handles rotation automatically")
    private fun rotateBitmapToOrientation(bitmap: Bitmap, targetOrientation: Int): Bitmap {
        // 直接返回原bitmap，不做任何旋转
        android.util.Log.w(TAG, "rotateBitmapToOrientation called but AUTO_MIRROR already handles rotation, returning original bitmap")
        return bitmap
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
        // 不需要重建VirtualDisplay，因为setupVirtualDisplay始终以全屏尺寸截屏
        // 裁剪在processImage中通过cropBitmap实现
        android.util.Log.i(TAG, "Capture region updated: ${if (region.isFullScreen) "FULL_SCREEN" else "${region.width}x${region.height} at (${region.x},${region.y})"}")
    }

    /**
     * 检查屏幕尺寸是否因旋转而变化
     * 注意：VirtualDisplay使用AUTO_MIRROR标志，旋转后自动适配新方向
     * 不需要重建VirtualDisplay（Android不允许同一MediaProjection上多次createVirtualDisplay）
     * 只需更新本地记录的屏幕尺寸，供外部查询使用
     * @return true如果检测到旋转
     */
    fun checkAndRecreateForRotation(): Boolean {
        if (!isCapturing) return false

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val newWidth = displayMetrics.widthPixels
        val newHeight = displayMetrics.heightPixels
        val newOrientation = if (newWidth > newHeight) 1 else 0

        // 检测尺寸变化（旋转时宽高互换）
        if (newWidth != screenWidth || newHeight != screenHeight) {
            android.util.Log.i(TAG, "Screen rotation detected: ${screenWidth}x${screenHeight} -> ${newWidth}x${newHeight}, orientation=${if (newOrientation==1) "landscape" else "portrait"}")
            screenWidth = newWidth
            screenHeight = newHeight
            screenDensity = displayMetrics.densityDpi
            displayOrientation = newOrientation

            // 注意：MediaProjection不能重复创建VirtualDisplay
            // 所以这里只更新屏幕尺寸记录，不重建VirtualDisplay
            // 截屏内容会通过AUTO_MIRROR自动适配，但坐标需要外部重新计算
            return true
        }
        return false
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
