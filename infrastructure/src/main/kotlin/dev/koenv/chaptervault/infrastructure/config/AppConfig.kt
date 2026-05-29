package dev.koenv.chaptervault.infrastructure.config

import dev.koenv.chaptervault.shared.format.ChapterFormat

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val storage: StorageConfig = StorageConfig(),
    val log: LogConfig = LogConfig(),
    val refresh: RefreshConfig = RefreshConfig(),
    val debug: DebugConfig = DebugConfig(),
    val auth: AuthConfig = AuthConfig(),
)

// Enable only during local development — never expose mock connectors in production
data class DebugConfig(
    val mockConnectorEnabled: Boolean = false,
)

// intervalHours <= 0 disables auto-refresh
data class RefreshConfig(
    val intervalHours: Int = 24,
)

data class ServerConfig(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    // empty = anyHost() for local dev; set explicit origins to restrict in production
    val corsOrigins: List<String> = emptyList(),
)

data class DatabaseConfig(
    val driver: String = "org.sqlite.JDBC",
    val url: String = "jdbc:sqlite:data/db/chaptervault.db",
    val maxPoolSize: Int = 5,
)

data class StorageConfig(
    val libraryPath: String = "data/library",
    val thumbnailsPath: String = "data/thumbnails",
    val defaultFormat: ChapterFormat = ChapterFormat.Cbz,
)

data class LogConfig(
    val level: String = "INFO",
)

data class AuthConfig(
    val rateLimiting: RateLimitConfig = RateLimitConfig(),
)

data class RateLimitConfig(
    val enabled: Boolean = true,
    val trustedNetworks: List<String> = listOf(
        "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
    ),
    val trustedProxies: List<String> = emptyList(),
    val login: EndpointLimitConfig = EndpointLimitConfig(maxAttempts = 10, windowMinutes = 15),
    val register: EndpointLimitConfig = EndpointLimitConfig(maxAttempts = 5, windowMinutes = 60),
)

data class EndpointLimitConfig(
    val maxAttempts: Int,
    val windowMinutes: Int,
)
