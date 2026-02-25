package dev.koenv.chaptervault.api.models.library

import kotlinx.serialization.Serializable

/**
 * Response for removing a series from the library.
 */
@Serializable
data class LibraryRemoveResponse(
    val id: String,
    val title: String,
    val inLibrary: Boolean
)
