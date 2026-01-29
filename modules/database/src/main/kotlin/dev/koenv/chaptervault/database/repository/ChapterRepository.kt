package dev.koenv.chaptervault.database.repository

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.database.entity.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID as JavaUUID

/**
 * Repository for chapter metadata and download tracking
 */
class ChapterRepository(private val database: Database) {

    fun initialize() {
        transaction(database) {
            SchemaUtils.create(ChapterTable)
        }
    }

    /**
     * Find chapter by source URL
     */
    fun findByUrl(url: String): CachedChapter? {
        return transaction(database) {
            val entity = ChapterEntity.find { ChapterTable.sourceUrl eq url }.firstOrNull()
            entity?.toCachedChapter()
        }
    }

    /**
     * Find chapter by internal ID
     */
    fun findById(id: JavaUUID): CachedChapter? {
        return transaction(database) {
            val entity = ChapterEntity.findById(id)
            entity?.toCachedChapter()
        }
    }

    /**
     * Find all chapters for a series
     */
    fun findBySeriesId(seriesId: JavaUUID): List<CachedChapter> {
        return transaction(database) {
            ChapterEntity.find { ChapterTable.seriesId eq seriesId }
                .map { it.toCachedChapter() }
        }
    }

    /**
     * Find downloaded chapters for a series
     */
    fun findDownloaded(seriesId: JavaUUID): List<CachedChapter> {
        return transaction(database) {
            ChapterEntity.find {
                (ChapterTable.seriesId eq seriesId) and
                (ChapterTable.downloadStatus eq DownloadStatus.DOWNLOADED.name)
            }.map { it.toCachedChapter() }
        }
    }

    /**
     * Find chapters not yet downloaded for a series
     */
    fun findNotDownloaded(seriesId: JavaUUID): List<CachedChapter> {
        return transaction(database) {
            ChapterEntity.find {
                (ChapterTable.seriesId eq seriesId) and
                (ChapterTable.downloadStatus eq DownloadStatus.NOT_DOWNLOADED.name)
            }.map { it.toCachedChapter() }
        }
    }

    /**
     * Save or update chapter metadata
     */
    fun save(metadata: ChapterMetadata, seriesId: JavaUUID): CachedChapter {
        return transaction(database) {
            val now = Instant.now()
            val series = SeriesEntity.findById(seriesId)
                ?: throw IllegalArgumentException("Series not found: $seriesId")

            val entity = ChapterEntity.find { ChapterTable.sourceUrl eq metadata.url }.firstOrNull()
                ?: ChapterEntity.new {
                    this.series = series
                    sourceUrl = metadata.url
                    downloadStatus = DownloadStatus.NOT_DOWNLOADED.name
                    createdAt = now
                }

            entity.apply {
                title = metadata.title
                chapterNumber = metadata.chapterNumber
                publishDate = metadata.publishDate
                pageCount = metadata.pageCount
                updatedAt = now
            }

            entity.toCachedChapter()
        }
    }

    /**
     * Save multiple chapters at once
     */
    fun saveAll(chapters: List<ChapterMetadata>, seriesId: JavaUUID): List<CachedChapter> {
        return transaction(database) {
            chapters.map { metadata ->
                val now = Instant.now()
                val series = SeriesEntity.findById(seriesId)
                    ?: throw IllegalArgumentException("Series not found: $seriesId")

                val entity = ChapterEntity.find { ChapterTable.sourceUrl eq metadata.url }.firstOrNull()
                    ?: ChapterEntity.new {
                        this.series = series
                        sourceUrl = metadata.url
                        downloadStatus = DownloadStatus.NOT_DOWNLOADED.name
                        createdAt = now
                    }

                entity.apply {
                    title = metadata.title
                    chapterNumber = metadata.chapterNumber
                    publishDate = metadata.publishDate
                    pageCount = metadata.pageCount
                    updatedAt = now
                }

                entity.toCachedChapter()
            }
        }
    }

    /**
     * Mark chapter as currently downloading
     */
    fun markDownloading(chapterId: JavaUUID) {
        transaction(database) {
            val entity = ChapterEntity.findById(chapterId)
                ?: throw IllegalArgumentException("Chapter not found: $chapterId")
            entity.downloadStatus = DownloadStatus.DOWNLOADING.name
            entity.updatedAt = Instant.now()
        }
    }

    /**
     * Mark chapter as successfully downloaded
     */
    fun markDownloaded(chapterId: JavaUUID, filePath: String, fileSize: Long, storageFormat: String) {
        transaction(database) {
            val entity = ChapterEntity.findById(chapterId)
                ?: throw IllegalArgumentException("Chapter not found: $chapterId")
            entity.downloadStatus = DownloadStatus.DOWNLOADED.name
            entity.downloadedAt = Instant.now()
            entity.filePath = filePath
            entity.fileSize = fileSize
            entity.storageFormat = storageFormat
            entity.updatedAt = Instant.now()
        }
    }

    /**
     * Mark chapter download as failed
     */
    fun markFailed(chapterId: JavaUUID) {
        transaction(database) {
            val entity = ChapterEntity.findById(chapterId)
                ?: throw IllegalArgumentException("Chapter not found: $chapterId")
            entity.downloadStatus = DownloadStatus.FAILED.name
            entity.updatedAt = Instant.now()
        }
    }

    /**
     * Reset chapter to not downloaded (for re-download)
     */
    fun resetDownloadStatus(chapterId: JavaUUID) {
        transaction(database) {
            val entity = ChapterEntity.findById(chapterId)
                ?: throw IllegalArgumentException("Chapter not found: $chapterId")
            entity.downloadStatus = DownloadStatus.NOT_DOWNLOADED.name
            entity.downloadedAt = null
            entity.filePath = null
            entity.fileSize = null
            entity.storageFormat = null
            entity.updatedAt = Instant.now()
        }
    }

    /**
     * Delete chapter by ID
     */
    fun delete(chapterId: JavaUUID) {
        transaction(database) {
            ChapterEntity.findById(chapterId)?.delete()
        }
    }

    /**
     * Delete all chapters for a series
     */
    fun deleteBySeriesId(seriesId: JavaUUID) {
        transaction(database) {
            ChapterEntity.find { ChapterTable.seriesId eq seriesId }.forEach { it.delete() }
        }
    }

    /**
     * Count chapters for a series
     */
    fun countBySeriesId(seriesId: JavaUUID): Long {
        return transaction(database) {
            ChapterEntity.find { ChapterTable.seriesId eq seriesId }.count()
        }
    }

    /**
     * Count downloaded chapters for a series
     */
    fun countDownloaded(seriesId: JavaUUID): Long {
        return transaction(database) {
            ChapterEntity.find {
                (ChapterTable.seriesId eq seriesId) and
                (ChapterTable.downloadStatus eq DownloadStatus.DOWNLOADED.name)
            }.count()
        }
    }

    private fun ChapterEntity.toCachedChapter(): CachedChapter {
        return CachedChapter(
            id = id.value,
            seriesId = series.id.value,
            sourceUrl = sourceUrl,
            title = title,
            chapterNumber = chapterNumber,
            publishDate = publishDate,
            pageCount = pageCount,
            downloadStatus = DownloadStatus.valueOf(downloadStatus),
            downloadedAt = downloadedAt,
            filePath = filePath,
            fileSize = fileSize,
            storageFormat = storageFormat,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}

/**
 * Cached chapter model with internal UUID
 */
data class CachedChapter(
    val id: JavaUUID,
    val seriesId: JavaUUID,
    val sourceUrl: String,
    val title: String,
    val chapterNumber: String,
    val publishDate: String?,
    val pageCount: Int?,
    val downloadStatus: DownloadStatus,
    val downloadedAt: Instant?,
    val filePath: String?,
    val fileSize: Long?,
    val storageFormat: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
