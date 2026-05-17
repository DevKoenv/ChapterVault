package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class ChapterDto(
    val id: String,
    val seriesId: String,
    val title: String,
    val chapterIndex: Double,
    val downloadStatus: String,
    val format: String? = null,
    val pageCount: Int? = null,
)
