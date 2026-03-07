package dev.koenv.chaptervault.api.models.library

import kotlinx.serialization.Serializable

/**
 * Request body for adding a series to the library.
 */
@Serializable
data class LibraryAddRequest(
    val seriesId: String,
    val autoDownload: Boolean = false
)
