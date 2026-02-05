package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table for storing cached series metadata.
 *
 * Series can exist in two states:
 * - **Cached**: Temporary metadata from search results, subject to cleanup
 * - **In Library**: User's collection, protected from cleanup
 */
object SeriesTable : UUIDTable(name = "series") {
    val sourceUrl = varchar("source_url", 512).uniqueIndex()
    val title = varchar("title", 256)
    val description = text("description").nullable()
    val author = varchar("author", 128).nullable()
    val coverUrl = varchar("cover_url", 512).nullable()
    val status = varchar("status", 32)
    val language = varchar("language", 16).nullable()
    val inLibrary = bool("in_library").default(false)
    val addedToLibraryAt = timestamp("added_to_library_at").nullable()
    val metadataFetchedAt = timestamp("metadata_fetched_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
