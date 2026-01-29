package dev.koenv.chaptervault.api.models.download

import dev.koenv.chaptervault.api.models.Pagination
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

/**
 * Response after creating a download job.
 */
@Serializable
data class CreateDownloadResponse(
    val downloadId: String,
    val status: String,
    val message: String
)

/**
 * Response for listing downloads.
 */
@Serializable
data class DownloadListResponse(
    val downloads: List<DownloadStatusResponse>,
    val pagination: Pagination
)

/**
 * Status of a download job.
 */
@Serializable
data class DownloadStatusResponse(
    val id: String,
    val taskType: String,
    val targetUrl: String,
    val seriesId: String?,
    val status: String,
    val message: String?,
    val progress: DownloadProgressDto,
    val error: String?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?
)

/**
 * Progress information for a download.
 */
@Serializable
data class DownloadProgressDto(
    val current: Int,
    val total: Int,
    val percentage: Int
)
