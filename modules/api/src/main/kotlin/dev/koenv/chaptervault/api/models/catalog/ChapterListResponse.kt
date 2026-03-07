package dev.koenv.chaptervault.api.models.catalog

import dev.koenv.chaptervault.api.models.Pagination
import kotlinx.serialization.Serializable

/**
 * Paginated list of chapters for a series.
 */
@Serializable
data class ChapterListResponse(
    val chapters: List<ChapterDto>,
    val pagination: Pagination
)
