package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

object ConfigValidator {
    private val log = LoggerFactory.getLogger(ConfigValidator::class.java)

    fun validate(config: AppConfig) {
        val errors = mutableListOf<String>()

        if (config.server.port !in 1..65535)
            errors += "server.port ${config.server.port} is not a valid port number"

        val storagePath = Paths.get(config.storage.basePath)
        try {
            Files.createDirectories(storagePath)
            if (!Files.isWritable(storagePath))
                errors += "storage.basePath '${config.storage.basePath}' is not writable"
        } catch (e: Exception) {
            errors += "storage.basePath '${config.storage.basePath}' cannot be created: ${e.message}"
        }

        if (config.database.url.isBlank())
            errors += "database.url must not be empty"

        if (errors.isNotEmpty()) {
            errors.forEach { log.error("Configuration error: $it") }
            error("Server startup aborted due to ${errors.size} configuration error(s)")
        }

        log.info("Config: port=${config.server.port} db=${config.database.url} storage=${config.storage.basePath} refresh=${config.refresh.intervalHours}h debug=${config.debug}")
    }
}
