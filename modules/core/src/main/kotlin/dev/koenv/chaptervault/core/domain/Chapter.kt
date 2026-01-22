package dev.koenv.chaptervault.core.domain

/**
 * Represents a single chapter within a series
 */
data class Chapter(
    val url: String,
    val seriesUrl: String,
    val title: String,
    val chapterNumber: String,
    val publishDate: String? = null
)
