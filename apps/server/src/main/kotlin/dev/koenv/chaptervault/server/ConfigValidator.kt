package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

object ConfigValidator {
    private val log = LoggerFactory.getLogger(ConfigValidator::class.java)

    fun validate(config: AppConfig) {
        val errors = mutableListOf<String>()

        if (config.server.port !in 1..65535) {
            errors += "server.port ${config.server.port} is not a valid port number"
        }

        val dbUrl = config.database.url
        if (dbUrl.isBlank()) {
            errors += "database.url must not be empty"
        } else if (dbUrl.startsWith("jdbc:sqlite:") && !dbUrl.removePrefix("jdbc:sqlite:").startsWith(":")) {
            val dbFilePath = dbUrl.removePrefix("jdbc:sqlite:")
            val dbDir = Paths.get(dbFilePath).parent
            if (dbDir != null) {
                try {
                    Files.createDirectories(dbDir)
                    if (!Files.isWritable(dbDir)) {
                        errors += "database directory '$dbDir' is not writable"
                    }
                } catch (e: Exception) {
                    errors += "database directory '$dbDir' cannot be created: ${e.message}"
                }
            }
        }

        for ((name, path) in listOf(
            "storage.libraryPath" to config.storage.libraryPath,
            "storage.thumbnailsPath" to config.storage.thumbnailsPath,
        )) {
            val storagePath = Paths.get(path)
            try {
                Files.createDirectories(storagePath)
                if (!Files.isWritable(storagePath)) {
                    errors += "$name '$path' is not writable"
                }
            } catch (e: Exception) {
                errors += "$name '$path' cannot be created: ${e.message}"
            }
        }

        val invalidCidrs = mutableListOf<String>()
        (config.auth.rateLimiting.trustedNetworks + config.auth.rateLimiting.trustedProxies).forEach { cidr ->
            runCatching {
                dev.koenv.chaptervault.shared.net
                    .CidrMatcher(cidr)
            }.onFailure {
                invalidCidrs += "auth.rateLimiting CIDR '$cidr' is invalid: ${it.message}"
            }
        }
        errors.addAll(invalidCidrs)

        if (errors.isNotEmpty()) {
            errors.forEach { log.error("Configuration error: $it") }
            error("Server startup aborted due to ${errors.size} configuration error(s)")
        }

        log.info(
            "Config: port=${config.server.port} db=${config.database.url} " +
                "library=${config.storage.libraryPath} thumbnails=${config.storage.thumbnailsPath} " +
                "refresh=${config.refresh.intervalHours}h debug=${config.debug} " +
                "rateLimiting=${config.auth.rateLimiting.enabled}",
        )
    }
}
