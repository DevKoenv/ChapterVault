package dev.koenv.chaptervault.core.addon

data class AddonInfo(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val state: AddonState,
    val connectorIds: List<String>,
    val depends: List<String>,
    val optionalDepends: List<String>,
    val errors: List<AddonError>
)
