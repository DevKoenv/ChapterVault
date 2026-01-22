package dev.koenv.chaptervault.api.models.response

import kotlinx.serialization.Serializable

@Serializable
data class SeriesSearchResponse(
    val url: String,
    val title: String,
    val description: String?,
    val coverUrl: String?
)
