package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class SeriesSearchResultDto(
    val externalId: String,
    val title: String,
    val coverUrl: String?,
    val description: String?,
)
