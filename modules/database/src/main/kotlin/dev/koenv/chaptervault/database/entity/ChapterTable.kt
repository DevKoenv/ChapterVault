package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Table for storing chapter metadata and download status
 */
object ChapterTable : UUIDTable("chapters") {
    val seriesId = reference("series_id", SeriesTable)
    val sourceUrl = varchar("source_url", 512).uniqueIndex()
    val title = varchar("title", 256)
    val chapterNumber = varchar("chapter_number", 32)
    val publishDate = varchar("publish_date", 32).nullable()
    val pageCount = integer("page_count").nullable()

    // Download tracking
    val downloadStatus = varchar("download_status", 32).default("NOT_DOWNLOADED")
    val downloadedAt = timestamp("downloaded_at").nullable()
    val filePath = varchar("file_path", 1024).nullable()
    val fileSize = long("file_size").nullable()
    val storageFormat = varchar("storage_format", 32).nullable()

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

/**
 * Download status for chapters
 */
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    PARTIAL
}
