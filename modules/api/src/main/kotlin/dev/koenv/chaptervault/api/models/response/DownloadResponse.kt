package dev.koenv.chaptervault.api.models.response

import kotlinx.serialization.Serializable

@Serializable
data class DownloadResponse(
    val taskId: String,
    val message: String
)
