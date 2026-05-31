package dev.koenv.chaptervault.infrastructure.extensions

data class RegistryCatalog(
    val schemaVersion: Int,
    val registryName: String,
    val extensions: List<CatalogEntry>,
)

data class CatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val jarUrl: String,
    val description: String = "",
    val author: String = "",
    val minServerVersion: String = "1.0.0",
)

data class ResolvedCatalogEntry(val entry: CatalogEntry, val registryName: String, val conflicting: Boolean = false)

class ConflictingExtensionException(id: String, registries: List<String>) :
    Exception("Extension '$id' found in multiple registries: ${registries.joinToString()}")
