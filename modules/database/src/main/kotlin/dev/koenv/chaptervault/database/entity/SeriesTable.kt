package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table for storing cached series metadata.
 *
 * Series can exist in two states:
 * - **Cached**: Temporary metadata from search results, subject to cleanup
 * - **In Library**: User's collection, protected from cleanup
 *
 * Identity is keyed on (connector, externalId) — stable across URL changes.
 */
object SeriesTable : UUIDTable(name = "series") {
    val connector = varchar("connector", 64)
    val externalId = varchar("external_id", 128)
    val sourceUrl = varchar("source_url", 512)
    val title = varchar("title", 256)
    val description = text("description").nullable()
    val author = varchar("author", 128).nullable()
    val coverUrl = varchar("cover_url", 512).nullable()
    val status = varchar("status", 32)
    val language = varchar("language", 16).nullable()
    val inLibrary = bool("in_library").default(false)
    val addedToLibraryAt = timestamp("added_to_library_at").nullable()
    val autoDownload = bool("auto_download").default(false)
    val chaptersFetchedAt = timestamp("chapters_fetched_at").nullable()
    val metadataFetchedAt = timestamp("metadata_fetched_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("idx_series_connector_external", connector, externalId)
    }
}
