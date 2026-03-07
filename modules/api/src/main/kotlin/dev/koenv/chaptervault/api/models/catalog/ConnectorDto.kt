package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Connector information for API consumers.
 */
@Serializable
data class ConnectorDto(
    val id: String,
    val name: String,
    val version: String,
    val features: ConnectorFeaturesDto,
    val priority: Int
)
