package dev.koenv.chaptervault.core

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Logger {

    enum class Level(val priority: Int) {
        TRACE(0),
        DEBUG(1),
        INFO(2),
        WARN(3),
        ERROR(4)
    }

    /** Logger configuration */
    data class Config(
        var level: Level = Level.DEBUG,
        var logFilePath: String? = null,
        var useColors: Boolean = true,
        var timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
        var jsonOutput: Boolean = false,
        var maxFileSizeMB: Int = 5
    )

    private val lock = ReentrantLock()
    private var config = Config()
    private var logFile: File? = null
    private val dateFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(config.timestampFormat)

    // ANSI colors
    private val colors = mapOf(
        Level.TRACE to "\u001B[37m", // White
        Level.DEBUG to "\u001B[36m", // Cyan
        Level.INFO to "\u001B[32m",  // Green
        Level.WARN to "\u001B[33m",  // Yellow
        Level.ERROR to "\u001B[31m"  // Red
    )
    private const val RESET = "\u001B[0m"

    /** Initialize logger with config */
    fun init(newConfig: Config) {
        lock.withLock {
            config = newConfig
            config.logFilePath?.let {
                logFile = File(it).apply { parentFile.mkdirs() }
            }
            info("Logger initialized with level ${config.level}")
        }
    }

    /** Set log level at runtime */
    fun setLevel(level: Level) {
        config.level = level
        info("Log level changed to $level")
    }

    /** Clear log file */
    fun clearLogFile() {
        lock.withLock {
            logFile?.writeText("")
            info("Log file cleared")
        }
    }

    /** Generic log function */
    private fun log(level: Level, message: String, tag: String? = null) {
        if (level.priority < config.level.priority) return
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val tagStr = tag?.let { " [$it]" } ?: ""
        val logLine = if (config.jsonOutput) {
            """{"timestamp":"$timestamp","level":"${level.name}","tag":"$tagStr","message":"$message"}"""
        } else {
            "[$timestamp] [${level.name}]$tagStr $message"
        }

        lock.withLock {
            // Console
            if (config.useColors && !config.jsonOutput) {
                println("${colors[level] ?: ""}$logLine$RESET")
            } else {
                println(logLine)
            }

            // File
            logFile?.let {
                rotateIfNeeded()
                PrintWriter(FileWriter(it, true)).use { writer ->
                    writer.println(logLine)
                }
            }
        }
    }


    // Convenience functions
    fun trace(message: String, tag: String? = null) = log(Level.TRACE, message, tag)
    fun debug(message: String, tag: String? = null) = log(Level.DEBUG, message, tag)
    fun info(message: String, tag: String? = null) = log(Level.INFO, message, tag)
    fun warn(message: String, tag: String? = null) = log(Level.WARN, message, tag)
    fun error(message: String, tag: String? = null) = log(Level.ERROR, message, tag)

    /** Optional log rotation if file exceeds max size */
    private fun rotateIfNeeded() {
        logFile?.takeIf { it.exists() }?.let { file ->
            val maxBytes = config.maxFileSizeMB * 1024L * 1024L
            if (file.length() > maxBytes) {
                val rotated = File("${file.absolutePath}.${System.currentTimeMillis()}.old")
                file.renameTo(rotated)
                file.createNewFile()
                info("Log rotated: ${rotated.name}")
            }
        }
    }
}
