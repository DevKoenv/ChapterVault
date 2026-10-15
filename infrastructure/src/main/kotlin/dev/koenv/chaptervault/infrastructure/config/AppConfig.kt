package dev.koenv.chaptervault.infrastructure.config

import dev.koenv.chaptervault.shared.format.ChapterFormat

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val storage: StorageConfig = StorageConfig(),
    val refresh: RefreshConfig = RefreshConfig(),
    val tasks: TasksConfig = TasksConfig(),
    val debug: DebugConfig = DebugConfig(),
    val auth: AuthConfig = AuthConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val updates: UpdateConfig = UpdateConfig(),
    val connectors: ConnectorsConfig = ConnectorsConfig(),
)

data class ConnectorsConfig(
    val mangadex: MangaDexConfig = MangaDexConfig(),
)

data class MangaDexConfig(
    // safe, suggestive, erotica, pornographic; searches only return works with one of these ratings
    val contentRatings: List<String> = listOf("safe", "suggestive"),
)

data class NotificationConfig(
    // true lets notification targets point at this machine (localhost, loopback, link-local); private LAN addresses always work
    val allowLocalTargets: Boolean = false,
)

data class UpdateConfig(
    // false stops the server from asking GitHub for the latest release
    val checkEnabled: Boolean = true,
)

// local dev only, never enable in production
data class DebugConfig(
    val mockConnectorEnabled: Boolean = false,
)

// intervalHours <= 0 disables auto-refresh
data class RefreshConfig(
    val intervalHours: Int = 24,
)

data class TasksConfig(
    // finished (completed, failed, cancelled) task rows older than this many days are deleted; 0 or less keeps them forever
    val retentionDays: Int = 30,
)

data class ServerConfig(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    // empty = anyHost() in dev, restrict with explicit origins in production
    val corsOrigins: List<String> = emptyList(),
)

data class DatabaseConfig(
    val driver: String = "org.sqlite.JDBC",
    val url: String = "jdbc:sqlite:data/db/chaptervault.db",
)

data class StorageConfig(
    val libraryPath: String = "data/library",
    val thumbnailsPath: String = "data/thumbnails",
    val defaultFormat: ChapterFormat = ChapterFormat.Cbz,
)

data class AuthConfig(
    val registrationEnabled: Boolean = false,
    val rateLimiting: RateLimitConfig = RateLimitConfig(),
    val sessionCleanupIntervalHours: Int = 24,
)

data class RateLimitConfig(
    val enabled: Boolean = true,
    val trustedNetworks: List<String> =
        listOf(
            "127.0.0.0/8",
            "::1/128",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
        ),
    val trustedProxies: List<String> = emptyList(),
    val login: EndpointLimitConfig = EndpointLimitConfig(maxAttempts = 10, windowMinutes = 15),
    val register: EndpointLimitConfig = EndpointLimitConfig(maxAttempts = 5, windowMinutes = 60),
)

data class EndpointLimitConfig(
    val maxAttempts: Int,
    val windowMinutes: Int,
)
