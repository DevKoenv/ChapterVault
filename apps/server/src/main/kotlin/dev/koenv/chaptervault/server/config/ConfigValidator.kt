package dev.koenv.chaptervault.server.config

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

private val logger = KotlinLogging.logger {}

object ConfigValidator {
    private const val MAX_LISTED = 5

    /** Names in the library path that are neither a series directory (named by its id) nor `.trash`, sorted. */
    internal fun foreignLibraryEntries(libraryPath: String): List<String> {
        val dir = Paths.get(libraryPath)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .map { it.fileName.toString() }
                .filter { name -> name != ".trash" && !(runCatching { UUID.fromString(name).toString() == name }.getOrDefault(false)) }
                .sorted()
                .toList()
        }
    }

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

        if (errors.isEmpty()) {
            val foreign = foreignLibraryEntries(config.storage.libraryPath)
            if (foreign.isNotEmpty()) {
                logger.warn {
                    "storage.libraryPath '${config.storage.libraryPath}' contains ${foreign.size} entries that are not " +
                        "ChapterVault's (${foreign.take(MAX_LISTED).joinToString()}${if (foreign.size > MAX_LISTED) ", ..." else ""}). " +
                        "They are left alone, but the path should hold ChapterVault's library and nothing else"
                }
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
            errors.forEach { logger.error { "Configuration error: $it" } }
            error("Server startup aborted due to ${errors.size} configuration error(s)")
        }

        logger.info {
            "Config: port=${config.server.port} db=${config.database.url} " +
                "library=${config.storage.libraryPath} thumbnails=${config.storage.thumbnailsPath} " +
                "refresh=${config.refresh.intervalHours}h debug=${config.debug} " +
                "rateLimiting=${config.auth.rateLimiting.enabled}"
        }
    }
}
