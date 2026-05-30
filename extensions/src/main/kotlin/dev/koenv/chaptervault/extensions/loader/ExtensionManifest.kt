package dev.koenv.chaptervault.extensions.loader

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    val minServerVersion: String,
    val description: String,
    val author: String,
    val priority: Int = 100,
    val capabilities: List<String>,
    val entryPoint: String,
)
