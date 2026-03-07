package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * List of chapters for a series.
 */
@Serializable
data class ChapterListResponse(
    val chapters: List<ChapterDto>
)
