package dev.koenv.chaptervault.core.repository

import dev.koenv.chaptervault.core.domain.SeriesMetadata
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
     * Save or update series metadata
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
}
