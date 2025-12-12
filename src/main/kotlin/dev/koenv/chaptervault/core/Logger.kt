package dev.koenv.chaptervault.core

import dev.koenv.chaptervault.config.Config
import dev.koenv.chaptervault.config.Level
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/**
 * Minimal SLF4J wrapper.
 *
 * - Call Logger.init(config.logger) very early in main().
 * - Archives previous latest.log on startup into: yyyy-MM-dd_HH-mm-ss_SSS.log.gz
 *   (deterministic timestamp-based name).
 * - Sets LoggerContext properties used by logback.xml and toggles console/file encoder patterns
 *   based on configuration.
 *
 * Supported config fields used here: level, directory, colors, timestampFormat, file.
 */
object Logger {
    private const val APP_NAME = "ChapterVault"

    @Volatile
    private var initialized = false

    private lateinit var cfg: Config.Logger
    private val appLogger by lazy { LoggerFactory.getLogger(APP_NAME) }

    fun init(loggerConfig: Config.Logger) {
        synchronized(this) {
            cfg = loggerConfig

            // Archive existing latest.log before Logback opens it (if file logging enabled)
            if (cfg.file) {
                try {
                    archiveLatestIfPresent(File(cfg.directory))
                } catch (ex: Exception) {
                    // Logging not configured yet
                    println("Logger: failed to archive existing latest.log: ${ex.message}")
                }
            }

            val lc = LoggerFactory.getILoggerFactory()
            if (lc is LoggerContext) {
                lc.putProperty("LOG_DIR", cfg.directory)
                lc.putProperty("APP_NAME", APP_NAME)
                lc.putProperty("TIMESTAMP_FORMAT", cfg.timestampFormat)

                // set root level from config (best-effort)
                try {
                    val lbLevel = ch.qos.logback.classic.Level.valueOf(cfg.level.name)
                    val rootLogger = lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
                    rootLogger.level = lbLevel
                } catch (_: Exception) { /* ignore */ }

                // toggle console colors (update encoder pattern) and attach/detach file appender
                try {
                    val rootLogger = lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger

                    val consoleAppender = rootLogger.getAppender("CONSOLE")
                    if (consoleAppender is ConsoleAppender<*>) {
                        val enc = consoleAppender.encoder
                        if (enc is PatternLayoutEncoder) {
                            enc.stop()
                            enc.pattern = consolePattern(cfg.colors, cfg.timestampFormat)
                            enc.context = lc
                            enc.start()
                        }
                    }

                    val fileAppender = rootLogger.getAppender("FILE")
                    if (!cfg.file) {
                        fileAppender?.stop()
                        if (fileAppender != null) rootLogger.detachAppender(fileAppender)
                    } else {
                        // nothing else needed for file appender: writes to latest.log
                    }
                } catch (_: Exception) { /* ignore best-effort modifications */
                }
            }

            initialized = true
        }

        appLogger.info("Logger initialized (level=${cfg.level}, dir='${cfg.directory}', file=${cfg.file}, colors=${cfg.colors})")
    }

    private fun consolePattern(colors: Boolean, timestampFormat: String): String {
        val base = "[%d{$timestampFormat}] [%level] [%X{moduleShort:-$APP_NAME}] %msg%n"
        return if (colors) "%highlight($base)" else base
    }

    private fun filePattern(timestampFormat: String): String =
        "[%d{$timestampFormat}] [%level] [%X{moduleShort:-$APP_NAME}] %msg%n%ex{full}"

    /**
     * Archive latest.log -> yyyy-MM-dd_HH-mm-ss_SSS.log.gz
     * Deterministic timestamp-based name so archives sort chronologically.
     */
    private fun archiveLatestIfPresent(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        val latest = File(dir, "latest.log")
        if (!latest.exists() || latest.length() == 0L) return

        val now = LocalDateTime.now()
        val ts = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS"))
        val gz = File(dir, "${ts}.log.gz")

        FileInputStream(latest).use { fis ->
            FileOutputStream(gz).use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    fis.copyTo(gos)
                }
            }
        }

        if (!latest.delete()) latest.deleteOnExit()
    }

    fun shutdown() {
        initialized = false
    }

    private fun requireInit() {
        if (!initialized) throw IllegalStateException("Logger.init() must be called before logging.")
    }

    private fun getLogger(name: String?) = if (name.isNullOrBlank()) appLogger else LoggerFactory.getLogger(name)

    /**
     * Put the provided loggerName (or APP_NAME if null) directly into MDC.moduleShort.
     * The logback pattern reads moduleShort and prints it as the single module prefix.
     */
    private inline fun withModuleShort(loggerName: String?, block: () -> Unit) {
        val value = if (loggerName.isNullOrBlank()) APP_NAME else loggerName
        MDC.put("moduleShort", value)
        try {
            block()
        } finally {
            MDC.remove("moduleShort")
        }
    }

    // Public API
    fun trace(msg: String, loggerName: String? = null) {
        requireInit()
        val logger = getLogger(loggerName)
        withModuleShort(loggerName) { if (logger.isTraceEnabled) logger.trace(msg) }
    }

    fun debug(msg: String, loggerName: String? = null) {
        requireInit()
        val logger = getLogger(loggerName)
        withModuleShort(loggerName) { if (logger.isDebugEnabled) logger.debug(msg) }
    }

    fun info(msg: String, loggerName: String? = null) {
        requireInit()
        val logger = getLogger(loggerName)
        withModuleShort(loggerName) { if (logger.isInfoEnabled) logger.info(msg) }
    }

    fun warn(msg: String, loggerName: String? = null) {
        requireInit()
        val logger = getLogger(loggerName)
        withModuleShort(loggerName) { if (logger.isWarnEnabled) logger.warn(msg) }
    }

    fun error(msg: String, loggerName: String? = null) {
        requireInit()
        val logger = getLogger(loggerName)
        withModuleShort(loggerName) { if (logger.isErrorEnabled) logger.error(msg) }
    }

    fun logException(ex: Throwable, loggerName: String? = null) {
        requireInit()
        val logger = getLogger(loggerName)
        withModuleShort(loggerName) { logger.error(ex.message ?: "exception", ex) }
    }

    fun isEnabled(level: Level): Boolean {
        requireInit()
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        return when (level) {
            Level.TRACE -> root.isTraceEnabled
            Level.DEBUG -> root.isDebugEnabled
            Level.INFO -> root.isInfoEnabled
            Level.WARN -> root.isWarnEnabled
            Level.ERROR -> root.isErrorEnabled
        }
    }
}