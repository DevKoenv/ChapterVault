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

        for ((label, rawPath) in listOf("libraryPath" to config.storage.libraryPath, "thumbnailsPath" to config.storage.thumbnailsPath)) {
            val storagePath = Paths.get(rawPath)
            try {
                Files.createDirectories(storagePath)
                if (!Files.isWritable(storagePath))
                    errors += "storage.$label '$rawPath' is not writable"
            } catch (e: Exception) {
                errors += "storage.$label '$rawPath' cannot be created: ${e.message}"
            }
        }

        if (config.database.url.isBlank())
            errors += "database.url must not be empty"

        if (errors.isNotEmpty()) {
            errors.forEach { log.error("Configuration error: $it") }
            error("Server startup aborted due to ${errors.size} configuration error(s)")
        }

        log.info("Config: port=${config.server.port} db=${config.database.url} library=${config.storage.libraryPath} thumbnails=${config.storage.thumbnailsPath} refresh=${config.refresh.intervalHours}h debug=${config.debug}")
    }
}
