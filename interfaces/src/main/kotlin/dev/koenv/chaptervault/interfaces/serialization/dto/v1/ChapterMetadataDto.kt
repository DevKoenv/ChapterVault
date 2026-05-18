package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class ChapterMetadataDto(
    val externalId: String,
    val title: String,
    val chapterIndex: Double,
    val pageCount: Int?,
)
