package dev.koenv.chaptervault.core.repository

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import java.util.UUID

/**
 * Port interface for chapter repository.
 * Implementations can use different database backends (H2, SQLite, PostgreSQL, etc.)
 */
interface ChapterRepositoryPort {

    /**
     * Initialize the repository (create tables, etc.)
     */
    fun initialize()

    /**
     * Find chapter by source URL
     */
    fun findByUrl(url: String): Chapter?

    /**
     * Find chapter by internal ID
     */
    fun findById(id: UUID): Chapter?

    /**
     * Find all chapters for a series, ordered by chapterIndex (nulls last)
     */
    fun findBySeriesId(seriesId: UUID): List<Chapter>

    /**
     * Find downloaded chapters for a series
     */
    fun findDownloaded(seriesId: UUID): List<Chapter>

    /**
     * Find chapters not yet downloaded for a series
     */
    fun findNotDownloaded(seriesId: UUID): List<Chapter>

    /**
     * Save or update chapter metadata.
     * Identity is keyed on (connectorId, metadata.externalId).
     */
    fun save(metadata: ChapterMetadata, seriesId: UUID, connectorId: String): Chapter

    /**
     * Save multiple chapters at once.
     * Identity is keyed on (connectorId, metadata.externalId).
     */
    fun saveAll(chapters: List<ChapterMetadata>, seriesId: UUID, connectorId: String): List<Chapter>

    /**
     * Mark chapter as currently downloading
     */
    fun markDownloading(chapterId: UUID)

    /**
     * Mark chapter as successfully downloaded
     */
    fun markDownloaded(chapterId: UUID, filePath: String, fileSize: Long, storageFormat: String)

    /**
     * Mark chapter download as failed
     */
    fun markFailed(chapterId: UUID)

    /**
     * Reset chapter to not downloaded (for re-download)
     */
    fun resetDownloadStatus(chapterId: UUID)

    /**
     * Delete chapter by ID
     */
    fun delete(chapterId: UUID)

    /**
     * Delete all chapters for a series
     */
    fun deleteBySeriesId(seriesId: UUID)

    /**
     * Count chapters for a series
     */
    fun countBySeriesId(seriesId: UUID): Long

    /**
     * Count downloaded chapters for a series
     */
    fun countDownloaded(seriesId: UUID): Long
}
