package dev.koenv.chaptervault.api.models.download

import kotlinx.serialization.Serializable

/**
 * Request to create a new download job.
 */
@Serializable
data class CreateDownloadRequest(
    val seriesId: String? = null,
    val sourceUrl: String? = null,
    val chapterIds: List<String>? = null
)
