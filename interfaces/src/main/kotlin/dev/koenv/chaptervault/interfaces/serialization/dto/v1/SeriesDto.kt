package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: String,
    val title: String,
    val connectorId: String,
    val externalId: String,
    val language: String,
    val status: String,
    val autoDownload: Boolean,
    val coverUrl: String? = null,
    val description: String? = null,
    val readingStatus: String? = null,
)
