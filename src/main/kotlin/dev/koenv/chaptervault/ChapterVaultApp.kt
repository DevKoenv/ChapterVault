package dev.koenv.chaptervault

import dev.koenv.chaptervault.config.Config
import dev.koenv.chaptervault.core.ConfigManager
import dev.koenv.chaptervault.core.Logger
import dev.koenv.chaptervault.core.eventbus.EventBus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point and lifecycle manager for ChapterVault.
 *
 * Responsible for:
 * 1. Initializing configuration
 * 2. Initializing logger
 * 3. Setting up the global event bus
 * 4. Loading plugins
 * 5. Starting the downloader subsystem
 * 6. Starting the OPDS server
 * 7. Graceful shutdown handling
 */
class ChapterVaultApp {

    private val shuttingDown = AtomicBoolean(false)

    /**
     * Starts the ChapterVault application.
     *
     * Initializes all subsystems in the correct order.
     * If any step fails, the application shuts down gracefully and rethrows the exception.
     */
    suspend fun run() {
        // 1. Load configuration
        ConfigManager.setEnvPrefix("CHAPTERVAULT")
        ConfigManager.enableEnvOverrides(true)
        ConfigManager.registerConfig<Config>("config/config.yaml")
        ConfigManager.loadAll()
        val config = ConfigManager.get<Config>()

        // 2. Initialize logger according to loaded config
        Logger.init(config.logger)
        Logger.info("Logger configured.")

        // 3. Initialize event bus
        EventBus.init()
        Logger.info("Event bus initialized.")

        Logger.info("ChapterVault fully started.")

        // Keep application running until shutdown
        awaitCancellation()
    }

    /**
     * Gracefully shuts down all subsystems.
     *
     * Idempotent: can be called multiple times safely.
     */
    suspend fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return

        Logger.info("Shutting down ChapterVault...")

        // 1. Shutdown event bus
        EventBus.shutdown()

        // 2. Flush and close logger
        Logger.info("Shutdown complete.")
        Logger.shutdown()

    }

    companion object {
        /**
         * JVM entry point.
         */
        @JvmStatic
        fun main(args: Array<String>) = runBlocking {
            val app = ChapterVaultApp()

            Runtime.getRuntime().addShutdownHook(Thread {
                runBlocking {
                    app.shutdown()
                }
            })

            try {
                // Start application
                app.run()
            } catch (ex: Exception) {
                // Log fatal error and rethrow
                Logger.error("Fatal error during startup: ${ex.message}")
                throw ex
            } finally {
                // Ensure shutdown on exit
                withContext(NonCancellable) {
                    app.shutdown()
                }
            }
        }
    }
}
