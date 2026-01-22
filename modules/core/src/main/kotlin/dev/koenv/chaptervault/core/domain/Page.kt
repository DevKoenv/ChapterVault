package dev.koenv.chaptervault.core.domain

/**
 * Represents a single page within a chapter
 * Note: This is used for metadata only. The actual binary data is handled separately.
 */
data class Page(
    val index: Int,
    val url: String,
    val mimeType: String = "image/jpeg"
)
