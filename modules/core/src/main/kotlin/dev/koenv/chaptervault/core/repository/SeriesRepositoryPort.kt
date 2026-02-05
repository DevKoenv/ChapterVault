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
    fun findByUrl(url: String): CachedSeries?

    /**
     * Find series by internal ID
     */
    fun findById(id: UUID): CachedSeries?

    /**
     * Save or update series metadata (full metadata).
     * If the series is already in the library, preserves that status.
     */
    fun save(metadata: SeriesMetadata, language: String? = null): CachedSeries

    /**
     * Get all cached series
     */
    fun findAll(): List<CachedSeries>

    /**
     * Delete series by ID
     */
    fun delete(id: UUID)

    // ==================== Cache Management ====================

    /**
     * Save a series from search results with minimal metadata.
     * Creates a new entry with cacheLevel=SEARCH_RESULT or updates existing if found.
     */
    fun saveFromSearch(result: SeriesSearchResult): CachedSeries

    /**
     * Save multiple series from search results.
     */
    fun saveAllFromSearch(results: List<SeriesSearchResult>): List<CachedSeries>

    /**
     * Find series with stale cache (not updated since the given time).
     * @param olderThan Only return series not updated since this time
     * @param excludeLibrary If true, exclude series marked as inLibrary
     */
    fun findStaleCache(olderThan: Instant, excludeLibrary: Boolean = true): List<CachedSeries>

    /**
     * Delete series with stale cache that are not in the library.
     * @param olderThan Delete series not updated since this time
     * @return Number of series deleted
     */
    fun deleteStaleCache(olderThan: Instant): Int

    // ==================== Library Management ====================

    /**
     * Add a series to the user's library.
     * @throws IllegalArgumentException if series not found
     */
    fun addToLibrary(id: UUID): CachedSeries

    /**
     * Remove a series from the user's library.
     * The series remains cached but is no longer protected from cleanup.
     * @throws IllegalArgumentException if series not found
     */
    fun removeFromLibrary(id: UUID): CachedSeries

    /**
     * Find all series that are in the user's library.
     */
    fun findAllInLibrary(): List<CachedSeries>
}
