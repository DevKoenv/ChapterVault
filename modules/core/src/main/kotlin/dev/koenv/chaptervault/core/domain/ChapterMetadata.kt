package dev.koenv.chaptervault.core.domain

/**
 * Metadata for a chapter (returned when browsing/listing chapters)
 * Does NOT include page URLs or binary data.
 */
data class ChapterMetadata(
    val url: String,
    val seriesUrl: String,
    val title: String,
    val chapterNumber: String,
    val externalId: String,
    val chapterIndex: Int? = null,
    val publishDate: String? = null,
    val pageCount: Int? = null  // Optional: some sites provide this during browsing
)
