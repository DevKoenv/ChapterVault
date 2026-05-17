package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class UpdateSeriesRequest(
    val autoDownload: Boolean? = null,
    val defaultFormat: String? = null,
)
