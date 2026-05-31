package dev.koenv.chaptervault.infrastructure.database.repositories

import java.time.Instant

data class ExtensionRegistryRecord(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val createdAt: Instant,
)
