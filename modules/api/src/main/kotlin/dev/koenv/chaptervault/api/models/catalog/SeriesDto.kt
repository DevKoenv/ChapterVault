package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Unified series DTO used across catalog and library endpoints.
 */
@Serializable
data class SeriesDto(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val tags: List<String>,
    val status: String,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val inLibrary: Boolean,
    val addedToLibraryAt: String?
)
