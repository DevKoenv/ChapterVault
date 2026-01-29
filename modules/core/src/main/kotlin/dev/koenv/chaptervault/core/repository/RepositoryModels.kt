package dev.koenv.chaptervault.core.repository

import dev.koenv.chaptervault.core.domain.SeriesStatus
import java.time.Instant
import java.util.UUID

/**
 * Cached series model with internal UUID.
 * Represents a series stored in the local database.
 */
data class CachedSeries(
    val id: UUID,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val status: SeriesStatus,
    val language: String?,
    val tags: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Download status for chapters.
 */
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    PARTIAL
}

/**
 * Cached chapter model with internal UUID.
 * Represents a chapter stored in the local database.
 */
data class CachedChapter(
    val id: UUID,
    val seriesId: UUID,
    val sourceUrl: String,
    val title: String,
    val chapterNumber: String,
    val publishDate: String?,
    val pageCount: Int?,
    val downloadStatus: DownloadStatus,
    val downloadedAt: Instant?,
    val filePath: String?,
    val fileSize: Long?,
    val storageFormat: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Task types for download operations.
 */
enum class TaskType {
    DOWNLOAD_CHAPTER,
    DOWNLOAD_SERIES,
    REFRESH_METADATA
}

/**
 * Task status for tracking progress.
 */
enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Persisted task model for download tracking.
 */
data class PersistedTask(
    val id: UUID,
    val taskType: TaskType,
    val targetUrl: String,
    val seriesId: UUID?,
    val chapterId: UUID?,
    val status: TaskStatus,
    val message: String?,
    val currentProgress: Int,
    val totalProgress: Int,
    val errorMessage: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?
) {
    val percentage: Int
        get() = if (totalProgress > 0) (currentProgress * 100) / totalProgress else 0
}
