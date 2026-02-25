package dev.koenv.chaptervault.api.models.download

import kotlinx.serialization.Serializable

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
