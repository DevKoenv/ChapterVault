package dev.koenv.chaptervault.infrastructure.config

import dev.koenv.chaptervault.shared.format.ChapterFormat
import org.yaml.snakeyaml.Yaml
import java.io.File

object ConfigLoader {
    fun load(configPath: String = "config/application.yaml"): AppConfig {
        val file = File(configPath)
        if (!file.exists()) return AppConfig()

        @Suppress("UNCHECKED_CAST")
        val map = Yaml().load<Map<String, Any>>(file.inputStream()) ?: return AppConfig()

        val server = (map["server"] as? Map<*, *>)?.let { s ->
            ServerConfig(
                port = (s["port"] as? Int) ?: 8080,
                host = (s["host"] as? String) ?: "0.0.0.0",
            )
        } ?: ServerConfig()

        val database = (map["database"] as? Map<*, *>)?.let { d ->
            DatabaseConfig(
                driver = (d["driver"] as? String) ?: "org.sqlite.JDBC",
                url = (d["url"] as? String) ?: "jdbc:sqlite:data/chaptervault.db",
                maxPoolSize = (d["maxPoolSize"] as? Int) ?: 5,
            )
        } ?: DatabaseConfig()

        val storage = (map["storage"] as? Map<*, *>)?.let { s ->
            StorageConfig(
                basePath = (s["basePath"] as? String) ?: "downloads",
                defaultFormat = (s["defaultFormat"] as? String)
                    ?.let { runCatching { ChapterFormat.fromString(it) }.getOrNull() }
                    ?: ChapterFormat.Cbz,
            )
        } ?: StorageConfig()

        val log = (map["log"] as? Map<*, *>)?.let { l ->
            LogConfig(level = (l["level"] as? String) ?: "INFO")
        } ?: LogConfig()

        return AppConfig(server = server, database = database, storage = storage, log = log)
    }
}
