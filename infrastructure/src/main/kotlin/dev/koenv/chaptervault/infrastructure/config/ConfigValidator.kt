package dev.koenv.chaptervault.infrastructure.config

import java.net.URI

private val MANGADEX_CONTENT_RATINGS = setOf("safe", "suggestive", "erotica", "pornographic")

internal fun AppConfig.validate() {
    val errors = mutableListOf<String>()

    if (server.port !in 1..65535) errors += "server.port must be in 1..65535 (got ${server.port})"
    if (server.host.isBlank()) errors += "server.host must not be blank"
    server.publicUrl?.takeUnless { isOrigin(it) }?.let {
        errors += "server.publicUrl must be an http or https address without a path, such as https://manga.example.com (got '$it')"
    }

    if (database.url.isBlank()) errors += "database.url must not be blank"

    if (storage.libraryPath.isBlank()) errors += "storage.libraryPath must not be blank"
    if (storage.thumbnailsPath.isBlank()) errors += "storage.thumbnailsPath must not be blank"

    val login = auth.rateLimiting.login
    if (login.maxAttempts < 1) errors += "auth.rateLimiting.login.maxAttempts must be >= 1 (got ${login.maxAttempts})"
    if (login.windowMinutes < 1) errors += "auth.rateLimiting.login.windowMinutes must be >= 1 (got ${login.windowMinutes})"

    val register = auth.rateLimiting.register
    if (register.maxAttempts < 1) errors += "auth.rateLimiting.register.maxAttempts must be >= 1 (got ${register.maxAttempts})"
    if (register.windowMinutes < 1) errors += "auth.rateLimiting.register.windowMinutes must be >= 1 (got ${register.windowMinutes})"

    val ratings = connectors.mangadex.contentRatings
    if (ratings.isEmpty()) {
        // MangaDex would apply its own default instead of the one documented here
        errors += "connectors.mangadex.contentRatings must list at least one of ${MANGADEX_CONTENT_RATINGS.joinToString()}"
    }
    ratings.filter { it.lowercase() !in MANGADEX_CONTENT_RATINGS }.forEach {
        errors += "connectors.mangadex.contentRatings must be one of ${MANGADEX_CONTENT_RATINGS.joinToString()} (got '$it')"
    }

    if (errors.isNotEmpty()) {
        error("Invalid configuration:\n" + errors.joinToString("\n") { "  - $it" })
    }
}

// the OPDS links are built as <publicUrl>/opds/v1/..., so a path, query or credentials in it would end up in every link
private fun isOrigin(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrEmpty() &&
        uri.rawUserInfo == null &&
        uri.rawPath.orEmpty() in setOf("", "/") &&
        uri.rawQuery == null &&
        uri.rawFragment == null
}
