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

data class DownloadPage(
    val url: String,
    val index: Int,
    val headers: Map<String, String> = emptyMap(),
)

data class DownloadResult(
    val pages: List<DownloadPage>,
)
