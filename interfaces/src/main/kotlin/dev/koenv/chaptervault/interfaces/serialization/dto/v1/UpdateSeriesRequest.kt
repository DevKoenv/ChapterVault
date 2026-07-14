package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class UpdateSeriesRequest(
    val autoDownload: Boolean? = null,
    val defaultFormat: String? = null,
    val title: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val upstreamStatus: String? = null,
    val genres: List<String>? = null,
    val clearFields: List<String>? = null,
)
