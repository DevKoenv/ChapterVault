package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Response for lookup operations that return multiple results.
 */
@Serializable
data class CatalogLookupResponse(
    val series: List<SeriesDto>,
    val source: String
)
