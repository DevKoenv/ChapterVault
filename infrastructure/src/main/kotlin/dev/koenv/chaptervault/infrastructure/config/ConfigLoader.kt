package dev.koenv.chaptervault.infrastructure.config

import java.io.File

object ConfigLoader {
    fun load(configPath: String = "config/application.yaml"): AppConfig {
        val file = File(configPath)
        if (!file.exists()) return AppConfig()
        // TODO: parse YAML into AppConfig
        return AppConfig()
    }
}
