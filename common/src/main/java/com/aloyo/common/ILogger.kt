package com.aloyo.common

/**
 * 日志接口
 * 定义统一的日志记录接口
 */
interface ILogger {

    /**
     * 记录DEBUG级别日志
     */
    fun debug(tag: String, message: String, throwable: Throwable? = null)

    /**
     * 记录INFO级别日志
     */
    fun info(tag: String, message: String, throwable: Throwable? = null)

    /**
     * 记录WARN级别日志
     */
    fun warn(tag: String, message: String, throwable: Throwable? = null)

    /**
     * 记录ERROR级别日志
     */
    fun error(tag: String, message: String, throwable: Throwable? = null)

    /**
     * 获取日志文件路径列表
     */
    fun getLogFiles(): List<String>

    /**
     * 导出日志为ZIP文件
     * @return ZIP文件路径，失败返回null
     */
    fun exportLogs(): String?

    /**
     * 清除所有日志文件
     */
    fun clearLogs()
}

/**
 * 日志级别枚举
 */
enum class LogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3)
}

/**
 * 日志条目数据类
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null
)
