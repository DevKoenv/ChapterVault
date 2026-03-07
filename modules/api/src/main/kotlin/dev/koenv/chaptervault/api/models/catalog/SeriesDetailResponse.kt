package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Unified detailed series response with chapters.
 */
@Serializable
data class SeriesDetailResponse(
    val id: String,
    val connector: String,
    val externalId: String,
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
    val addedToLibraryAt: String?,
    val chapters: List<ChapterDto>
)
