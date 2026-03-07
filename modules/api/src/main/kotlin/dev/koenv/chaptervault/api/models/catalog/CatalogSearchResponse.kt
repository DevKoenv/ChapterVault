package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Response for catalog search (keyword or URL lookup).
 */
@Serializable
data class CatalogSearchResponse(
    val series: List<SeriesDto>,
    val connector: String?
)
