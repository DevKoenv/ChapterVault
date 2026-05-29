package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class AddSeriesRequest(
    val connectorId: String,
    val externalId: String,
    val language: String = "en",
    val autoDownload: Boolean = false,
)
