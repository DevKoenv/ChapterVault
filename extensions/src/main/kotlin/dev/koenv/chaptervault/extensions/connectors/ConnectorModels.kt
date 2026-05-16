package dev.koenv.chaptervault.extensions.connectors

data class SeriesSearchResult(
    val externalId: String,
    val title: String,
    val coverUrl: String? = null,
    val description: String? = null,
)

data class SeriesMetadata(
    val externalId: String,
    val title: String,
    val coverUrl: String? = null,
    val description: String? = null,
)

data class ChapterMetadata(
    val externalId: String,
    val title: String,
    val chapterIndex: Double,
    val pageCount: Int? = null,
)

data class DownloadResult(
    val pageUrls: List<String>,
    val totalPages: Int,
)
