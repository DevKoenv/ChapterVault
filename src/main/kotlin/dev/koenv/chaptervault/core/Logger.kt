package dev.koenv.chaptervault.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.withLock

/**
 * Simple application logger with optional file output, JSON mode and colorized console output.
 *
 * Thread-safe operations are guarded by a ReentrantLock and an atomic init flag is used to avoid
 * duplicate initialization. Log rotation archives the previous `latest.log` file.
 */
object Logger {

    enum class Level(val priority: Int) {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4)
    }

    data class Config(
        var level: Level = Level.DEBUG,
        var logDirPath: String = "logs",
        var useColors: Boolean = true,
        var timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
        var jsonOutput: Boolean = false,
        var logToFile: Boolean = true,
        var tagAsField: Boolean = true
    )

    private var initialized = false
    private val initInProgress = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private var config = Config()
    private var logFile: File? = null
    private var fileWriter: PrintWriter? = null

    /**
     * Cached DateTimeFormatter to avoid allocating a new formatter per log line.
     * It is updated whenever the config.timestampFormat changes.
     */
    private var cachedDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(config.timestampFormat)

    private val colors = mapOf(
        Level.TRACE to "\u001B[37m",
        Level.DEBUG to "\u001B[36m",
        Level.INFO to "\u001B[32m",
        Level.WARN to "\u001B[33m",
        Level.ERROR to "\u001B[31m"
    )
    private const val RESET = "\u001B[0m"

    private val sbTls = ThreadLocal.withInitial { StringBuilder(512) }

    /**
     * Create a PrintWriter that appends to the given file using UTF-8 and auto-flush enabled.
     *
     * @param file target file to append logs to
     * @return a PrintWriter that appends in UTF-8 and flushes automatically
     */
    private fun createWriter(file: File): PrintWriter =
        PrintWriter(BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8)), true)

    /**
     * Safely close the currently open file writer if any.
     *
     * This swallows exceptions during close to keep logging resilient.
     */
    private fun closeWriter() {
        try {
            // flush buffered content before closing
            fileWriter?.flush()
            fileWriter?.close()
        } catch (_: Exception) {
            // ignore close errors, nothing we can do
        } finally {
            // ensure we don't reuse a closed writer
            fileWriter = null
        }
    }

    /**
     * Compresses the provided `latest` log file to a gzipped file named with a timestamp and a short random suffix.
     * If compression fails, attempts a fallback rename to preserve the existing log contents.
     *
     * The caller is responsible for ensuring the writer was closed prior to calling this method.
     *
     * @param latest file to archive
     */
    private fun archiveLatest(latest: File) {
        val timestamp = Instant.now().toEpochMilli()
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        val gzFile = File(latest.parentFile, "${timestamp}-${suffix}.log.gz")
        try {
            // copy and compress the current latest log to a new gz file
            FileInputStream(latest).use { fis ->
                FileOutputStream(gzFile).use { fos ->
                    GZIPOutputStream(fos).use { gos ->
                        fis.copyTo(gos)
                    }
                }
            }
            // remove original after successful compression
            latest.delete()
        } catch (ex: Exception) {
            // if compression fails, try a non-compressed fallback rename
            val fallback = File(latest.parentFile, "${timestamp}-${suffix}.log")
            try {
                latest.renameTo(fallback)
            } catch (_: Exception) {
                // if fallback fails, leave original file untouched
            }
        }
    }

    /**
     * Initialize the logger with the provided configuration.
     *
     * Initialization is performed under a lock to ensure atomic transition of file writer state.
     * Potentially slow operations like archiving the previous latest.log are done outside the lock,
     * then writer reopening is performed under the lock again to avoid race conditions.
     *
     * @param newConfig configuration to apply during initialization
     */
    fun init(newConfig: Config) {
        var infoMessage: String? = null
        var toArchive: File? = null

        lock.withLock {
            // close any existing writer before changing configuration
            closeWriter()

            // apply new config and rebuild cached formatter
            config = newConfig
            cachedDateFormatter = DateTimeFormatter.ofPattern(config.timestampFormat)

            if (config.logToFile) {
                // ensure log directory exists
                val dir = File(config.logDirPath).apply { mkdirs() }
                val latest = File(dir, "latest.log")
                if (latest.exists()) {
                    // schedule archiving of the existing latest.log after releasing the lock
                    toArchive = latest
                    logFile = null
                    fileWriter = null
                } else {
                    // create an empty latest.log file and open writer
                    try {
                        latest.writeText("")
                    } catch (_: Exception) {
                        // ignore file creation errors; writer creation will handle existence
                    }
                    logFile = latest
                    fileWriter = createWriter(latest)
                }
            } else {
                // running without file logging
                logFile = null
                fileWriter = null
            }

            initialized = true
            infoMessage =
                "Logger initialized with level ${config.level} (logDir='${config.logDirPath}', fileLogging=${config.logToFile})"
        }

        // perform archive outside the lock to avoid holding the lock during IO
        toArchive?.let { latest ->
            try {
                archiveLatest(latest)
            } catch (_: Exception) {
                // ignore archive failures
            }

            // recreate latest.log and reopen writer if still configured to log to file
            lock.withLock {
                if (config.logToFile && (logFile == null || !logFile!!.exists())) {
                    val dir = File(config.logDirPath).apply { mkdirs() }
                    val recreated = File(dir, "latest.log")
                    try {
                        recreated.writeText("")
                    } catch (_: Exception) {
                        // ignore write errors
                    }
                    logFile = recreated
                    fileWriter = createWriter(recreated)
                }
            }
        }

        // log that initialization completed
        infoMessage?.let { info(it) }
    }

    /**
     * Ensure the logger is initialized.
     *
     * One thread performs initialization while others spin-wait using LockSupport.parkNanos
     * to avoid heavy blocking syscalls. This avoids initializing multiple times concurrently.
     */
    private fun ensureInitialized() {
        if (initialized) return
        if (initInProgress.compareAndSet(false, true)) {
            try {
                // perform default initialization if another thread hasn't done it
                init(Config())
            } finally {
                initInProgress.set(false)
            }
        } else {
            // wait briefly (non-busy) until initialization completes
            while (!initialized) {
                try {
                    LockSupport.parkNanos(100_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /**
     * Shutdown the logger by flushing and closing the writer and marking the logger uninitialized.
     *
     * This is safe to call multiple times.
     */
    fun shutdown() {
        lock.withLock {
            try {
                fileWriter?.flush()
            } catch (_: Exception) {
                // ignore flush errors during shutdown
            }
            closeWriter()
            logFile = null
            initialized = false
        }
    }

    /**
     * Change the runtime log level.
     *
     * @param level new minimum level to log
     */
    fun setLevel(level: Level) {
        var infoMessage: String? = null
        lock.withLock {
            config.level = level
            infoMessage = "Log level changed to $level"
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Enable or disable logging to file at runtime.
     *
     * This will open or close the underlying writer as needed.
     *
     * @param enabled whether to enable file logging
     */
    fun logToFile(enabled: Boolean) {
        var infoMessage: String? = null
        var toCreate: Boolean = false
        lock.withLock {
            if (config.logToFile == enabled) return
            config.logToFile = enabled
            if (!enabled) {
                // disable file output immediately
                closeWriter()
                logFile = null
                infoMessage = "File logging disabled"
            } else {
                // mark that we need to create/open the file writer after releasing lock
                toCreate = true
                infoMessage = "File logging enabled"
            }
        }
        if (toCreate) {
            lock.withLock {
                val dir = File(config.logDirPath).apply { mkdirs() }
                val latest = File(dir, "latest.log")
                if (!latest.exists()) {
                    try {
                        latest.writeText("")
                    } catch (_: Exception) {
                        // ignore creation errors, try to open writer anyway
                    }
                }
                logFile = latest
                fileWriter = createWriter(latest)
            }
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Toggle JSON output mode. When enabled, logs are emitted as compact JSON objects.
     *
     * @param enabled whether to format logs as JSON
     */
    fun jsonOutput(enabled: Boolean) {
        var infoMessage: String? = null
        lock.withLock {
            if (config.jsonOutput == enabled) return
            config.jsonOutput = enabled
            infoMessage = "JSON output set to $enabled"
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Change the directory used for file logging and reopen writer if needed.
     *
     * @param path new directory path for logs
     */
    fun setLogDirPath(path: String) {
        var infoMessage: String? = null
        lock.withLock {
            if (config.logDirPath == path) return
            val previous = config.logDirPath
            config.logDirPath = path
            if (config.logToFile) {
                // switch writer to the new directory
                closeWriter()
                val dir = File(path).apply { mkdirs() }
                val latest = File(dir, "latest.log")
                if (!latest.exists()) {
                    try {
                        latest.writeText("")
                    } catch (_: Exception) {
                        // ignore write errors
                    }
                }
                logFile = latest
                fileWriter = createWriter(latest)
            }
            infoMessage = "Log directory changed from $previous to $path"
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Enable or disable ANSI color output to console.
     *
     * @param enabled whether to use colors on console
     */
    fun setUseColors(enabled: Boolean) {
        var infoMessage: String? = null
        lock.withLock {
            if (config.useColors == enabled) return
            config.useColors = enabled
            infoMessage = "Use colors set to $enabled"
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Set the timestamp format used for human-readable logs.
     *
     * The formatter is rebuilt under the lock so subsequent log lines use the new pattern immediately.
     *
     * @param format pattern for DateTimeFormatter
     */
    fun setTimestampFormat(format: String) {
        var infoMessage: String? = null
        lock.withLock {
            if (config.timestampFormat == format) return
            config.timestampFormat = format
            // rebuild cached formatter here so the change is effective immediately
            cachedDateFormatter = DateTimeFormatter.ofPattern(format)
            infoMessage = "Timestamp format changed to $format"
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Configure whether the tag is emitted as a separate JSON field or prepended to the message.
     *
     * @param enabled true to send tag as its own JSON field
     */
    fun setTagAsField(enabled: Boolean) {
        var infoMessage: String? = null
        lock.withLock {
            if (config.tagAsField == enabled) return
            config.tagAsField = enabled
            infoMessage = "Tag-as-field set to $enabled"
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Clear the current log file contents.
     *
     * This closes the writer, truncates the file and reopens the writer to continue logging.
     */
    fun clearLogFile() {
        var infoMessage: String? = null
        lock.withLock {
            if (!config.logToFile) {
                infoMessage = "File logging is disabled; nothing to clear"
            } else {
                // close writer so we can safely truncate
                closeWriter()
                try {
                    logFile?.writeText("")
                } catch (_: Exception) {
                    // ignore truncation errors
                }
                // reopen writer after truncation
                logFile?.let {
                    fileWriter = createWriter(it)
                }
                infoMessage = "Log file cleared"
            }
        }
        infoMessage?.let { info(it) }
    }

    /**
     * Escape a string for safe inclusion in a JSON string value.
     *
     * Uses a reusable StringBuilder from thread-local storage to minimize allocations.
     *
     * @param sb builder to append escaped characters to
     * @param s input string to escape
     */
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
                else -> {
                    if (ch.code < 0x20) {
                        // control characters are escaped as unicode code points
                        sb.append("\\u").append(String.format("%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
    }

    /**
     * Central logging routine that formats and emits a log line to console and (optionally) file.
     *
     * Console output is performed outside the lock to reduce latency and contention.
     * File writes are done under the lock to ensure consistency of the file writer and to avoid interleaved writes.
     * Colors are not applied when JSON output is enabled to keep machine-readable logs clean.
     *
     * @param level severity level for the message
     * @param message textual content of the log
     * @param tag optional tag/class that produced the log
     */
    private fun log(level: Level, message: String, tag: String? = null) {
        ensureInitialized()

        // skip messages below the configured level
        if (level.priority < config.level.priority) return

        // format timestamp using cached formatter for efficiency
        val timestamp = LocalDateTime.now().format(cachedDateFormatter)

        // when not outputting JSON, include tag in the human-readable line
        val tagStr = if (!config.jsonOutput && tag != null) " [$tag]" else ""

        val logLine = if (config.jsonOutput) {
            // build a compact JSON object using thread-local StringBuilder
            val sb = sbTls.get()
            sb.setLength(0)
            sb.append('{')
            sb.append("\"timestamp\":\""); jsonEscape(sb, timestamp); sb.append('"')
            sb.append(",\"level\":\""); jsonEscape(sb, level.name); sb.append('"')

            if (tag != null && config.tagAsField) {
                // emit tag as its own JSON field
                sb.append(",\"tag\":\""); jsonEscape(sb, tag); sb.append('"')
                sb.append(",\"message\":\""); jsonEscape(sb, message); sb.append('"')
            } else {
                // prepend tag to message when tagAsField is false
                val msgWithTag = if (tag != null && !config.tagAsField) "[$tag] $message" else message
                sb.append(",\"message\":\""); jsonEscape(sb, msgWithTag); sb.append('"')
            }

            sb.append('}')
            sb.toString()
        } else {
            // simple human-readable format
            "[$timestamp] [${level.name}]$tagStr $message"
        }

        // print to console; colors only when enabled and not in JSON mode
        if (config.useColors && !config.jsonOutput) {
            println("${colors[level] ?: ""}$logLine$RESET")
        } else {
            println(logLine)
        }

        // write to file under lock to prevent interleaved writes between threads
        lock.withLock {
            try {
                fileWriter?.println(logLine)
            } catch (_: Exception) {
                // swallow IO errors to keep logging non-fatal
            }
        }
    }

    /**
     * Check whether the provided level would be logged under current configuration.
     *
     * @param level level to test
     * @return true if messages at this level will be emitted
     */
    fun isEnabled(level: Level): Boolean {
        ensureInitialized()
        return level.priority >= config.level.priority
    }

    /**
     * Log a Throwable's stacktrace as a single log message.
     *
     * The throwable's full stacktrace is captured by calling `ex.printStackTrace(PrintWriter(sw))`
     * into a `StringWriter`. The resulting (potentially multi-line) string is passed to the central
     * logger as the message body. Note that when `jsonOutput` is enabled special characters and newlines
     * will be escaped in the JSON string value.
     *
     * @param ex throwable to log
     * @param level severity to log the exception at (defaults to ERROR)
     * @param tag optional tag/class that produced the exception
     */
    fun logException(ex: Throwable, level: Level = Level.ERROR, tag: String? = null) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        // capture full stacktrace as the message body and log it
        log(level, sw.toString(), tag)
    }

    /**
     * Convenience wrappers for logging at specific levels.
     */
    fun trace(message: String, tag: String? = null) = log(Level.TRACE, message, tag)
    fun debug(message: String, tag: String? = null) = log(Level.DEBUG, message, tag)
    fun info(message: String, tag: String? = null) = log(Level.INFO, message, tag)
    fun warn(message: String, tag: String? = null) = log(Level.WARN, message, tag)
    fun error(message: String, tag: String? = null) = log(Level.ERROR, message, tag)
}