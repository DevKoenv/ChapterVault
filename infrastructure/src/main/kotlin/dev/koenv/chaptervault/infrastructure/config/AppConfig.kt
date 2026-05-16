package dev.koenv.chaptervault.infrastructure.config

import dev.koenv.chaptervault.shared.format.ChapterFormat

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val storage: StorageConfig = StorageConfig(),
    val log: LogConfig = LogConfig(),
)

data class ServerConfig(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
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
