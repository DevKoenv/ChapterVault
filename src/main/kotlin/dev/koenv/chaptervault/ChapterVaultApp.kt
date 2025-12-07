package dev.koenv.chaptervault

import dev.koenv.chaptervault.config.Config
import dev.koenv.chaptervault.core.*
import kotlinx.coroutines.runBlocking
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
        try {
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
            // EventBus.init()
            Logger.info("Event bus initialized.")

            // 4. Load plugins
            // PluginManager.loadAll()
            Logger.info("Plugins loaded successfully.")

            // 5. Start downloader subsystem
            // Downloader.init(config.downloads)
            Logger.info("Downloader subsystem started.")

            // 6. Start OPDS server
            // OpdsServer.start(config.opds)
            Logger.info("OPDS server started.")

            Logger.info("ChapterVault fully started.")
        } catch (ex: Exception) {
            Logger.error("Startup failed. Performing graceful shutdown.")
            Logger.logException(ex)
            shutdown()
            throw ex // Ensures JVM exits with non-zero status
        }
    }

    /**
     * Gracefully shuts down all subsystems.
     *
     * Idempotent: can be called multiple times safely.
     */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return

        Logger.info("Shutting down ChapterVault…")

        // 1. Stop OPDS server
        // OpdsServer.stop()

        // 2. Stop downloader subsystem
        // Downloader.shutdown()

        // 3. Unload plugins
        // PluginManager.unloadAll()

        // 4. Shutdown event bus
        // EventBus.shutdown()

        // 5. Flush and close logger
        Logger.info("Shutdown complete.")
        Logger.shutdown()
    }

    companion object {
        /**
         * JVM entry point.
         *
         * Registers a shutdown hook to ensure graceful termination on SIGTERM/SIGINT.
         */
        @JvmStatic
        fun main(args: Array<String>) = runBlocking {
            val app = ChapterVaultApp()

            // Register JVM shutdown hook without using runBlocking to avoid nested coroutine dispatchers
            Runtime.getRuntime().addShutdownHook(Thread {
                app.shutdown()
            })

            // Start application
            app.run()
        }
    }
}