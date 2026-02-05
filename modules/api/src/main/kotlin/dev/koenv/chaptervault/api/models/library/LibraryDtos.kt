package dev.koenv.chaptervault.api.models.library

import dev.koenv.chaptervault.api.models.Pagination
import kotlinx.serialization.Serializable

/**
 * Response for listing downloaded series.
 */
@Serializable
data class LibrarySeriesListResponse(
    val series: List<LibrarySeriesDto>,
    val pagination: Pagination
)

/**
 * Series summary for library view.
 */
@Serializable
data class LibrarySeriesDto(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val tags: List<String>,
    val status: String,
    val downloadedChapterCount: Int,
    val totalChapterCount: Int,
    val inLibrary: Boolean,
    val addedToLibraryAt: String?
)

/**
 * Detailed series response with downloaded chapters.
 */
@Serializable
data class LibrarySeriesDetailResponse(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val tags: List<String>,
    val status: String,
    val downloadedChapterCount: Int,
    val totalChapterCount: Int,
    val inLibrary: Boolean,
    val addedToLibraryAt: String?,
    val chapters: List<LibraryChapterDto>
)

/**
 * Downloaded chapter info for library view.
 */
@Serializable
data class LibraryChapterDto(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val chapterNumber: String,
    val publishDate: String?,
    val pageCount: Int?,
    val downloadedAt: String?,
    val filePath: String?,
    val fileSize: Long?
)

/**
 * Response for adding a series to the library.
 */
@Serializable
data class LibraryAddResponse(
    val id: String,
    val title: String,
    val inLibrary: Boolean,
    val addedToLibraryAt: String?
)

/**
 * Response for removing a series from the library.
 */
@Serializable
data class LibraryRemoveResponse(
    val id: String,
    val title: String,
    val inLibrary: Boolean
)
