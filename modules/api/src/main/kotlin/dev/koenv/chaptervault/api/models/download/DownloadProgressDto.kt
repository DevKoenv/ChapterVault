package dev.koenv.chaptervault.api.models.download

import kotlinx.serialization.Serializable

/**
 * Progress information for a download.
 */
@Serializable
data class DownloadProgressDto(
    val current: Int,
    val total: Int,
    val percentage: Int
)
