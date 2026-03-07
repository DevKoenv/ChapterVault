package dev.koenv.chaptervault.core.domain

/**
 * Result from searching for series
 */
data class SeriesSearchResult(
    val url: String,
    val title: String,
    val externalId: String,
    val description: String? = null,
    val coverUrl: String? = null
)
