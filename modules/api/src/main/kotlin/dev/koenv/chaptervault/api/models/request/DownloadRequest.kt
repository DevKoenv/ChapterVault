package dev.koenv.chaptervault.api.models.request

import kotlinx.serialization.Serializable

@Serializable
data class DownloadRequest(
    val url: String
)
