package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Unified chapter DTO used across catalog and library endpoints.
 */
@Serializable
data class ChapterDto(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val chapterNumber: String,
    val publishDate: String?,
    val pageCount: Int?,
    val downloadStatus: String,
    val downloadedAt: String?,
    val filePath: String?,
    val fileSize: Long?
)
