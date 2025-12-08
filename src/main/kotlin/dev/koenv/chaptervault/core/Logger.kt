package dev.koenv.chaptervault.core

import dev.koenv.chaptervault.config.Config
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.withLock

/**
 * Thread-safe application logger.
 *
 * Supports console output with optional ANSI color codes, JSON-formatted logs,
 * file logging with log rotation, and runtime configuration via [Config].
 *
 * Logging levels are defined in [Level]. The logger can be initialized with
 * custom settings using [init].
 *
 * Example usage:
 * ```
 * Logger.init(Logger.Config(level = Logger.Level.INFO, colors = true))
 * Logger.info("Application started")
 * ```
 */
object Logger {

    /**
     * Logging severity levels.
     *
     * Higher priority values indicate higher severity. Messages with a lower
     * priority than the configured level will be ignored.
     */
    enum class Level(val priority: Int) { TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4) }

    /** Whether the logger has been initialized */
    private var initialized = false

    /** Lock for thread-safe access to internal state and file writes */
    private val lock = ReentrantLock()

    /** Current logger configuration */
    private var config = Config.Logger()

    /** Current log file handle (if file logging is enabled) */
    private var logFile: File? = null

    /** Writer for appending logs to the log file */
    private var writer: PrintWriter? = null

    /** Cached [DateTimeFormatter] to avoid repeated allocations */
    private var formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(config.timestampFormat)

    /** ANSI color codes mapped to logging levels */
    private val colorsMap = mapOf(
        Level.TRACE to "\u001B[37m",
        Level.DEBUG to "\u001B[36m",
        Level.INFO to "\u001B[32m",
        Level.WARN to "\u001B[33m",
        Level.ERROR to "\u001B[31m"
    )

    /** ANSI reset code */
    private const val RESET = "\u001B[0m"

    /** Thread-local buffer for building JSON log strings efficiently */
    private val sbTls = ThreadLocal.withInitial { StringBuilder(512) }

    // ------------------------ Public API ------------------------

    /**
     * Initialize the logger with the given [Config].
     *
     * If file logging is enabled and a previous `latest.log` exists, it will be
     * archived in a gzipped file with a timestamp suffix.
     *
     * @param newConfig Configuration to apply. Overwrites previous configuration.
     */
    fun init(newConfig: Config.Logger) {
        var toArchive: File? = null
        lock.withLock {
            closeWriter()
            config = newConfig
            formatter = DateTimeFormatter.ofPattern(config.timestampFormat)

            if (config.file) {
                val dir = File(config.directory).apply { mkdirs() }
                val latest = File(dir, "latest.log")
                if (latest.exists()) toArchive = latest
                logFile = latest
                writer = createWriter(latest)
            }

            initialized = true
        }

        toArchive?.let { archiveLatest(it) }
        info("Logger initialized with level ${config.level} (directory='${config.directory}', file=${config.file})")
    }

    /**
     * Flush and close the log file writer, and mark the logger as uninitialized.
     *
     * Safe to call multiple times. File logging will stop after shutdown.
     */
    fun shutdown() = lock.withLock {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
        closeWriter()
        logFile = null
        initialized = false
    }

    /**
     * Clear the current log file contents.
     *
     * Closes the writer, truncates the file, and reopens it if file logging is enabled.
     */
    fun clearLogFile() = lock.withLock {
        if (!config.file) {
            info("File logging disabled; nothing to clear")
            return@withLock
        }
        closeWriter()
        logFile?.writeText("")
        writer = logFile?.let { createWriter(it) }
        info("Log file cleared")
    }

    /**
     * Check whether messages of the given [level] would be logged under current configuration.
     *
     * @param level Log level to test.
     * @return True if messages at this level are enabled, false otherwise.
     */
    fun isEnabled(level: Level): Boolean {
        ensureInitialized()
        return level.priority >= config.level.priority
    }

    /**
     * Log a [Throwable] with its full stack trace.
     *
     * @param ex Throwable to log.
     * @param level Severity level (defaults to [Level.ERROR]).
     * @param tag Optional tag to identify the source of the exception.
     */
    fun logException(ex: Throwable, level: Level = Level.ERROR, tag: String? = null) {
        val sw = StringWriter().also { ex.printStackTrace(PrintWriter(it)) }
        log(level, sw.toString(), tag)
    }

    /** Convenience logging methods for each level */
    fun trace(message: String, tag: String? = null) = log(Level.TRACE, message, tag)
    fun debug(message: String, tag: String? = null) = log(Level.DEBUG, message, tag)
    fun info(message: String, tag: String? = null) = log(Level.INFO, message, tag)
    fun warn(message: String, tag: String? = null) = log(Level.WARN, message, tag)
    fun error(message: String, tag: String? = null) = log(Level.ERROR, message, tag)

    // ------------------------ Internal Helpers ------------------------

    /**
     * Ensure the logger is initialized. If not, it initializes with default [Config].
     */
    private fun ensureInitialized() {
        if (!initialized) {
            lock.withLock {
                if (!initialized) init(Config.Logger())
            }
        }
    }

    /** Create a UTF-8 [PrintWriter] for the given file with auto-flush enabled */
    private fun createWriter(file: File): PrintWriter =
        PrintWriter(BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8)), true)

    /** Close the current file writer safely */
    private fun closeWriter() {
        try {
            writer?.flush(); writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    /**
     * Compress the provided log file into a gzipped archive with timestamp + random suffix.
     *
     * If compression fails, the file remains unmodified.
     *
     * @param latest File to archive.
     */
    private fun archiveLatest(latest: File) {
        val timestamp = Instant.now().toEpochMilli()
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        val gzFile = File(latest.parentFile, "${timestamp}-${suffix}.log.gz")
        try {
            FileInputStream(latest).use { fis ->
                FileOutputStream(gzFile).use { fos ->
                    GZIPOutputStream(fos).use { gos -> fis.copyTo(gos) }
                }
            }
            latest.delete()
        } catch (e: Exception) {
            log(Level.ERROR, "Failed to archive log file '${latest.absolutePath}': ${e.message}", "Logger")
        }
    }

    /**
     * Central logging function.
     *
     * Formats the log message according to the configuration and outputs to console and/or file.
     *
     * @param level Severity level.
     * @param message Log message text.
     * @param tag Optional tag to identify the source.
     */
    private fun log(level: Level, message: String, tag: String? = null) {
        ensureInitialized()
        if (level.priority < config.level.priority) return

        val timestamp = LocalDateTime.now().format(formatter)
        val tagStr = if (!config.json && tag != null) " [$tag]" else ""
        val logLine = if (config.json) buildJsonLog(level, message, tag, timestamp)
        else "[$timestamp] [${level.name}]$tagStr $message"

        if (config.colors && !config.json) println("${colorsMap[level] ?: ""}$logLine$RESET") else println(logLine)

        lock.withLock {
            try {
                writer?.println(logLine)
            } catch (_: Exception) {
            }
        }
    }

    /** Build a compact JSON representation of a log message */
    private fun buildJsonLog(level: Level, message: String, tag: String?, timestamp: String): String {
        val sb = sbTls.get()
        sb.setLength(0)
        sb.append('{')
        sb.append("\"timestamp\":\""); jsonEscape(sb, timestamp); sb.append('"')
        sb.append(",\"level\":\""); jsonEscape(sb, level.name); sb.append('"')
        if (tag != null && config.tagAsField) {
            sb.append(",\"tag\":\""); jsonEscape(sb, tag); sb.append('"')
            sb.append(",\"message\":\""); jsonEscape(sb, message); sb.append('"')
        } else {
            val msgWithTag = if (tag != null) "[$tag] $message" else message
            sb.append(",\"message\":\""); jsonEscape(sb, msgWithTag); sb.append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    /** Escape string for safe JSON inclusion */
    private fun jsonEscape(sb: StringBuilder, s: String) {
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u000C' -> sb.append("\\f")
                // Escape control characters, U+2028, and U+2029
                else -> if (ch.code < 0x20 || ch == '\u2028' || ch == '\u2029') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
}
