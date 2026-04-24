package com.aloyo.logger

import com.aloyo.common.ILogger
import com.aloyo.common.LogEntry
import com.aloyo.common.LogLevel
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ALOYO日志系统实现
 * 支持多级别日志、本地文件存储、日志轮转和导出
 * 使用单线程写入器保证线程安全和写入顺序
 */
class ALoyoLogger private constructor(
    private val logDir: File,
    private val minLogLevel: LogLevel = LogLevel.DEBUG,
    private val maxFileSizeBytes: Long = 5 * 1024 * 1024,
    private val maxRetentionDays: Int = 7
) : ILogger {

    companion object {
        private const val TAG = "ALoyoLogger"
        private const val LOG_FILE_PREFIX = "aloyo_"
        private const val LOG_FILE_EXTENSION = ".log"
        private const val EXPORT_FILE_NAME = "aloyo_logs_export.zip"

        @Volatile
        private var instance: ALoyoLogger? = null

        /**
         * 获取日志器单例
         * @param logDir 日志存储目录
         * @param minLogLevel 最低日志级别
         */
        fun getInstance(logDir: File, minLogLevel: LogLevel = LogLevel.DEBUG): ALoyoLogger {
            return instance ?: synchronized(this) {
                instance ?: ALoyoLogger(logDir, minLogLevel).also { instance = it }
            }
        }
    }

    // 日志条目队列，用于异步写入
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()

    // 单线程写入执行器，保证写入顺序
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ALoyoLogWriter").apply { isDaemon = true }
    }

    // 日期格式化器
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // 当前日志文件写入器
    @Volatile
    private var currentFileWriter: FileWriter? = null

    // 当前日志文件日期
    @Volatile
    private var currentDate: String = ""

    init {
        // 确保日志目录存在
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        // 启动日志清理定时任务
        scheduleLogCleanup()
        // 处理队列中的日志
        startLogProcessor()
    }

    override fun debug(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.DEBUG, tag, message, throwable)
    }

    override fun info(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.INFO, tag, message, throwable)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.WARN, tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    /**
     * 记录日志
     * 将日志条目加入队列，由后台线程异步写入文件
     */
    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.priority < minLogLevel.priority) return

        val throwableStr = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwableStr
        )

        // 同时输出到Logcat
        logToLogcat(entry)

        // 加入异步写入队列
        logQueue.offer(entry)
    }

    /**
     * 输出日志到Logcat
     */
    private fun logToLogcat(entry: LogEntry) {
        val logMessage = buildLogMessage(entry)
        when (entry.level) {
            LogLevel.DEBUG -> android.util.Log.d(entry.tag, logMessage)
            LogLevel.INFO -> android.util.Log.i(entry.tag, logMessage)
            LogLevel.WARN -> android.util.Log.w(entry.tag, logMessage)
            LogLevel.ERROR -> android.util.Log.e(entry.tag, logMessage)
        }
    }

    /**
     * 构建日志消息字符串
     */
    private fun buildLogMessage(entry: LogEntry): String {
        val sb = StringBuilder()
        sb.append("[${entry.level.name}] ")
        sb.append("${entry.tag}: ${entry.message}")
        if (entry.throwable != null) {
            sb.append("\n${entry.throwable}")
        }
        return sb.toString()
    }

    /**
     * 启动日志处理器
     * 从队列中取出日志条目并写入文件
     */
    private fun startLogProcessor() {
        writeExecutor.execute {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val entry = logQueue.poll()
                    if (entry != null) {
                        writeToFile(entry)
                    } else {
                        // 队列为空，短暂休眠
                        Thread.sleep(50)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error processing log entry", e)
                }
            }
        }
    }

    /**
     * 将日志条目写入文件
     */
    @Synchronized
    private fun writeToFile(entry: LogEntry) {
        try {
            val dateStr = dateFormat.format(Date(entry.timestamp))
            // 日期变化时切换日志文件
            if (dateStr != currentDate) {
                currentFileWriter?.close()
                currentDate = dateStr
                val logFile = File(logDir, "$LOG_FILE_PREFIX$dateStr$LOG_FILE_EXTENSION")
                currentFileWriter = FileWriter(logFile, true)
            }

            // 检查文件大小，超过限制则轮转
            val writer = currentFileWriter ?: return
            val logFile = File(logDir, "$LOG_FILE_PREFIX$currentDate$LOG_FILE_EXTENSION")
            if (logFile.exists() && logFile.length() > maxFileSizeBytes) {
                writer.close()
                // 重命名旧文件
                val rotatedFile = File(logDir, "$LOG_FILE_PREFIX${currentDate}_1$LOG_FILE_EXTENSION")
                logFile.renameTo(rotatedFile)
                // 创建新文件
                val newFile = File(logDir, "$LOG_FILE_PREFIX$currentDate$LOG_FILE_EXTENSION")
                currentFileWriter = FileWriter(newFile, true)
            }

            // 写入格式化的日志行
            val timeStr = timeFormat.format(Date(entry.timestamp))
            val line = "$timeStr [${entry.level.name}] ${entry.tag}: ${entry.message}"
            val finalWriter = currentFileWriter ?: return
            finalWriter.write(line)
            finalWriter.write("\n")
            if (entry.throwable != null) {
                finalWriter.write(entry.throwable)
                finalWriter.write("\n")
            }
            finalWriter.flush()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error writing log to file", e)
        }
    }

    /**
     * 定时清理过期日志文件
     */
    private fun scheduleLogCleanup() {
        val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ALoyoLogCleanup").apply { isDaemon = true }
        }
        cleanupExecutor.scheduleAtFixedRate({
            try {
                cleanOldLogs()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error cleaning old logs", e)
            }
        }, 0, 24, TimeUnit.HOURS)
    }

    /**
     * 清理超过保留天数的日志文件
     */
    private fun cleanOldLogs() {
        val cutoffTime = System.currentTimeMillis() - (maxRetentionDays * 24 * 60 * 60 * 1000L)
        logDir.listFiles()?.filter { file ->
            file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION) && file.lastModified() < cutoffTime
        }?.forEach { file ->
            file.delete()
            android.util.Log.i(TAG, "Deleted old log file: ${file.name}")
        }
    }

    override fun getLogFiles(): List<String> {
        return logDir.listFiles()
            ?.filter { it.name.startsWith(LOG_FILE_PREFIX) && it.name.endsWith(LOG_FILE_EXTENSION) }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    override fun exportLogs(): String? {
        return try {
            val exportFile = File(logDir, EXPORT_FILE_NAME)
            if (exportFile.exists()) {
                exportFile.delete()
            }

            ZipOutputStream(exportFile.outputStream()).use { zos ->
                logDir.listFiles()
                    ?.filter { it.name.startsWith(LOG_FILE_PREFIX) && it.name.endsWith(LOG_FILE_EXTENSION) }
                    ?.forEach { file ->
                        zos.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
            }
            exportFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error exporting logs", e)
            null
        }
    }

    override fun clearLogs() {
        logDir.listFiles()
            ?.filter { it.name.startsWith(LOG_FILE_PREFIX) }
            ?.forEach { it.delete() }
        currentFileWriter?.close()
        currentFileWriter = null
        currentDate = ""
    }

    /**
     * 关闭日志器，释放资源
     */
    fun shutdown() {
        writeExecutor.shutdown()
        try {
            if (!writeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                writeExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            writeExecutor.shutdownNow()
        }
        currentFileWriter?.close()
        currentFileWriter = null
    }
}
