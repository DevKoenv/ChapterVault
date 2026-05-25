package dev.koenv.chaptervault.infrastructure.config

import dev.koenv.chaptervault.shared.format.ChapterFormat

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val storage: StorageConfig = StorageConfig(),
    val log: LogConfig = LogConfig(),
    val refresh: RefreshConfig = RefreshConfig(),
    val debug: DebugConfig = DebugConfig(),
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
    val url: String = "jdbc:sqlite:data/chaptervault.db",
    val maxPoolSize: Int = 5,
)

data class StorageConfig(
    val basePath: String = "downloads",
    val defaultFormat: ChapterFormat = ChapterFormat.Cbz,
)

data class LogConfig(
    val level: String = "INFO",
)
