package dev.koenv.chaptervault.api.models.library

import kotlinx.serialization.Serializable

/**
 * Response for adding a series to the library.
 */
@Serializable
data class LibraryAddResponse(
    val id: String,
    val title: String,
    val inLibrary: Boolean,
    val addedToLibraryAt: String?,
    val taskId: String? = null
)
