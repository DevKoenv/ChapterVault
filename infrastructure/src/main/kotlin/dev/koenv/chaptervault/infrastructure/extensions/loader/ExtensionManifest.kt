package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.kernel.extension.ExtensionConfigField

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
    val config: List<ExtensionConfigField> = emptyList(),
)
