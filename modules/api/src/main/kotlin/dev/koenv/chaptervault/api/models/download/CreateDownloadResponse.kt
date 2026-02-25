package dev.koenv.chaptervault.api.models.download

import kotlinx.serialization.Serializable

/**
 * Response after creating a download job.
 */
@Serializable
data class CreateDownloadResponse(
    val downloadId: String,
    val status: String,
    val message: String
)
