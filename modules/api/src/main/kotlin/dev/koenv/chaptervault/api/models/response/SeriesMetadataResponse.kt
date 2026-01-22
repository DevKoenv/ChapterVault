package dev.koenv.chaptervault.api.models.response

import kotlinx.serialization.Serializable

@Serializable
data class SeriesMetadataResponse(
    val url: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val tags: List<String>,
    val status: String
)
