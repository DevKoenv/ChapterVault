package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Connector feature flags.
 */
@Serializable
data class ConnectorFeaturesDto(
    val search: Boolean,
    val download: Boolean,
    val pageCount: Boolean,
    val requiresAuth: Boolean
)
