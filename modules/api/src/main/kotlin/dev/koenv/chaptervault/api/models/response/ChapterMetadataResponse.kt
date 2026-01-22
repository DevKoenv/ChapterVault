package dev.koenv.chaptervault.api.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ChapterMetadataResponse(
    val url: String,
    val seriesUrl: String,
    val title: String,
    val chapterNumber: String,
    val publishDate: String?,
    val pageCount: Int?
)
