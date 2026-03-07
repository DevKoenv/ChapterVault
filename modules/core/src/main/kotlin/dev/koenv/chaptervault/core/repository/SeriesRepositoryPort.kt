package dev.koenv.chaptervault.core.repository

import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import java.time.Instant
import java.util.UUID

/**
 * Port interface for series repository.
 * Implementations can use different database backends (H2, SQLite, PostgreSQL, etc.)
 */
interface SeriesRepositoryPort {

    /**
     * Initialize the repository (create tables, etc.)
     */
    fun initialize()

    /**
     * Find series by source URL
     */
    fun findByUrl(url: String): Series?

    /**
     * Find series by internal ID
     */
    fun findById(id: UUID): Series?

    /**
     * Upsert series from full metadata using merge semantics.
     * Non-null values always win over null — existing non-null fields are never overwritten with null.
     * Preserves inLibrary status if already set.
     * Identity is keyed on (connectorId, metadata.externalId).
     */
    fun upsert(metadata: SeriesMetadata, connectorId: String, language: String? = null): Series

    /**
     * Get all cached series
     */
    fun findAll(): List<Series>

    /**
     * Delete series by ID
     */
    fun delete(id: UUID)

    // ==================== Cache Management ====================

    /**
     * Upsert a series from search results using merge semantics.
     * Non-null values always win — existing non-null fields are never overwritten with null.
     * Identity is keyed on (connectorId, result.externalId).
     */
    fun upsertFromSearch(result: SeriesSearchResult, connectorId: String): Series

    /**
     * Upsert multiple series from search results using merge semantics.
     * Identity is keyed on (connectorId, result.externalId).
     */
    fun upsertAllFromSearch(results: List<SeriesSearchResult>, connectorId: String): List<Series>

    /**
     * Find series with stale cache (not updated since the given time).
     * @param olderThan Only return series not updated since this time
     * @param excludeLibrary If true, exclude series marked as inLibrary
     */
    fun findStaleCache(olderThan: Instant, excludeLibrary: Boolean = true): List<Series>

    /**
     * Delete series with stale cache that are not in the library.
     * @param olderThan Delete series not updated since this time
     * @return Number of series deleted
     */
    fun deleteStaleCache(olderThan: Instant): Int

    // ==================== Library Management ====================

    /**
     * Add a series to the user's library.
     * @param autoDownload Whether new chapters should be downloaded automatically
     * @throws IllegalArgumentException if series not found
     */
    fun addToLibrary(id: UUID, autoDownload: Boolean = false): Series

    /**
     * Remove a series from the user's library.
     * The series remains cached but is no longer protected from cleanup.
     * @throws IllegalArgumentException if series not found
     */
    fun removeFromLibrary(id: UUID): Series

    /**
     * Find all series that are in the user's library.
     */
    fun findAllInLibrary(): List<Series>

    /**
     * Stamp the chaptersFetchedAt timestamp on a series to now.
     */
    fun stampChaptersFetchedAt(seriesId: UUID)
}
