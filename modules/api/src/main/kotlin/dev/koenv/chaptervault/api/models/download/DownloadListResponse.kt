package dev.koenv.chaptervault.api.models.download

import dev.koenv.chaptervault.api.models.Pagination
import kotlinx.serialization.Serializable

/**
 * Response for listing downloads.
 */
@Serializable
data class DownloadListResponse(
    val downloads: List<DownloadStatusResponse>,
    val pagination: Pagination
)
