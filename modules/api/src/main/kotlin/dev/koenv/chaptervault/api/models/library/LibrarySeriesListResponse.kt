package dev.koenv.chaptervault.api.models.library

import dev.koenv.chaptervault.api.models.catalog.SeriesDto
import kotlinx.serialization.Serializable

/**
 * Response for listing series in the user's library.
 */
@Serializable
data class LibrarySeriesListResponse(
    val series: List<SeriesDto>
)
