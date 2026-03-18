package dev.koenv.chaptervault.core.addon

data class AddonManifest(
    val id: String,
    val name: String = "Unknown",
    val version: String = "unknown",
    val description: String? = null,
    val author: String? = null,
    val apiVersion: Int,
    val main: String,
    val depends: List<String> = emptyList(),
    val optionalDepends: List<String> = emptyList()
)
