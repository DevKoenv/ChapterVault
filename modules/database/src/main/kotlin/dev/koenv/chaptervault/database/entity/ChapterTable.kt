package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table for storing chapter metadata and download status.
 *
 * Each chapter belongs to a [SeriesTable] and tracks its download state,
 * file location, and storage format.
 *
 * Identity is keyed on (connector, externalId) — stable across URL changes.
 */
object ChapterTable : UUIDTable("chapters") {
    val seriesId = reference("series_id", SeriesTable)
    val connector = varchar("connector", 64)
    val externalId = varchar("external_id", 128)
    val sourceUrl = varchar("source_url", 512)
    val title = varchar("title", 256)
    val chapterNumber = varchar("chapter_number", 32)
    val chapterIndex = integer("chapter_index").nullable()
    val publishDate = text("publish_date").nullable()
    val pageCount = integer("page_count").nullable()
    val downloadStatus = varchar("download_status", 32).default("NOT_DOWNLOADED")
    val downloadedAt = timestamp("downloaded_at").nullable()
    val filePath = varchar("file_path", 1024).nullable()
    val fileSize = long("file_size").nullable()
    val storageFormat = varchar("storage_format", 32).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("idx_chapters_connector_external", connector, externalId)
        index("idx_chapters_series_id", false, seriesId)
    }
}
