package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Table for storing cached series metadata
 * Caches static information that doesn't change frequently
 */
object SeriesTable : UUIDTable("series") {
    val sourceUrl = varchar("source_url", 512).uniqueIndex()
    val title = varchar("title", 256)
    val description = text("description").nullable()
    val author = varchar("author", 128).nullable()
    val coverUrl = varchar("cover_url", 512).nullable()
    val status = varchar("status", 32)
    val language = varchar("language", 16).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
