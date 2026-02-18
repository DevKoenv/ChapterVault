package dev.koenv.chaptervault.api.models.library

import dev.koenv.chaptervault.api.models.Pagination
import dev.koenv.chaptervault.api.models.catalog.SeriesDto
import kotlinx.serialization.Serializable

/**
 * Response for listing series in the user's library.
 */
@Serializable
data class LibrarySeriesListResponse(
    val series: List<SeriesDto>,
    val pagination: Pagination
)

/**
 * Response for adding a series to the library.
 */
@Serializable
data class LibraryAddResponse(
    val id: String,
    val title: String,
    val inLibrary: Boolean,
    val addedToLibraryAt: String?
)

/**
 * Response for removing a series from the library.
 */
@Serializable
data class LibraryRemoveResponse(
    val id: String,
    val title: String,
    val inLibrary: Boolean
)
