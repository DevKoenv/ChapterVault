package dev.koenv.chaptervault.core.domain

/**
 * Full metadata for a series (returned when fetching series details)
 */
data class SeriesMetadata(
    val url: String,
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
    val status: SeriesStatus = SeriesStatus.UNKNOWN
)
