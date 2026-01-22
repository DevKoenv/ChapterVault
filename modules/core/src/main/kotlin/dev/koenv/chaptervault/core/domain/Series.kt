package dev.koenv.chaptervault.core.domain

/**
 * Represents a series (comic, manga, ebook collection)
 */
data class Series(
    val url: String,
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val tags: List<String> = emptyList()
)
