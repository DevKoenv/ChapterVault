package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table for storing chapter metadata and download status.
 *
 * Each chapter belongs to a [SeriesTable] and tracks its download state,
 * file location, and storage format.
 */
object ChapterTable : UUIDTable("chapters") {
    val seriesId = reference("series_id", SeriesTable)
    val sourceUrl = varchar("source_url", 512).uniqueIndex()
    val title = varchar("title", 256)
    val chapterNumber = varchar("chapter_number", 32)
    val publishDate = varchar("publish_date", 32).nullable()
    val pageCount = integer("page_count").nullable()
    val downloadStatus = varchar("download_status", 32).default("NOT_DOWNLOADED")
    val downloadedAt = timestamp("downloaded_at").nullable()
    val filePath = varchar("file_path", 1024).nullable()
    val fileSize = long("file_size").nullable()
    val storageFormat = varchar("storage_format", 32).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
