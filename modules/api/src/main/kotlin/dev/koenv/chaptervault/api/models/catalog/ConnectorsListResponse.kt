package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Response for listing available connectors.
 */
@Serializable
data class ConnectorsListResponse(
    val connectors: List<ConnectorDto>
)
