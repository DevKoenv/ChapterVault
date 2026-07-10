package dev.koenv.chaptervault.infrastructure.config

private val VALID_LOG_LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")

internal fun AppConfig.validate() {
    val errors = mutableListOf<String>()

    if (server.port !in 1..65535) errors += "server.port must be in 1..65535 (got ${server.port})"
    if (server.host.isBlank()) errors += "server.host must not be blank"

    if (database.url.isBlank()) errors += "database.url must not be blank"
    if (database.maxPoolSize < 1) errors += "database.maxPoolSize must be >= 1 (got ${database.maxPoolSize})"

    if (storage.libraryPath.isBlank()) errors += "storage.libraryPath must not be blank"
    if (storage.thumbnailsPath.isBlank()) errors += "storage.thumbnailsPath must not be blank"

    if (log.level.uppercase() !in VALID_LOG_LEVELS) {
        errors += "log.level must be one of ${VALID_LOG_LEVELS.joinToString()} (got '${log.level}')"
    }

    val login = auth.rateLimiting.login
    if (login.maxAttempts < 1) errors += "auth.rateLimiting.login.maxAttempts must be >= 1 (got ${login.maxAttempts})"
    if (login.windowMinutes < 1) errors += "auth.rateLimiting.login.windowMinutes must be >= 1 (got ${login.windowMinutes})"

    val register = auth.rateLimiting.register
    if (register.maxAttempts < 1) errors += "auth.rateLimiting.register.maxAttempts must be >= 1 (got ${register.maxAttempts})"
    if (register.windowMinutes < 1) errors += "auth.rateLimiting.register.windowMinutes must be >= 1 (got ${register.windowMinutes})"

    if (errors.isNotEmpty()) {
        error("Invalid configuration:\n" + errors.joinToString("\n") { "  - $it" })
    }
}
