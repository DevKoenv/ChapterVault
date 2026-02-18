package dev.koenv.chaptervault.database.repository

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.repository.Chapter
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.DownloadStatus
import dev.koenv.chaptervault.database.entity.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Exposed-based implementation of ChapterRepositoryPort.
 * Uses H2/SQLite/PostgreSQL via Exposed ORM.
 */
class ChapterRepository(private val database: Database) : ChapterRepositoryPort {

    override fun initialize() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(ChapterTable)
        }
    }

    override fun findByUrl(url: String): Chapter? {
        return transaction(database) {
            val entity = ChapterEntity.find { ChapterTable.sourceUrl eq url }.firstOrNull()
            entity?.toChapter()
        }
    }

    override fun findById(id: UUID): Chapter? {
        return transaction(database) {
            val entity = ChapterEntity.findById(id)
            entity?.toChapter()
        }
    }

    override fun findBySeriesId(seriesId: UUID): List<Chapter> {
        return transaction(database) {
            ChapterEntity.find { ChapterTable.seriesId eq seriesId }
                .map { it.toChapter() }
        }
    }

    override fun findDownloaded(seriesId: UUID): List<Chapter> {
        return transaction(database) {
            ChapterEntity.find {
                (ChapterTable.seriesId eq seriesId) and
                (ChapterTable.downloadStatus eq DownloadStatus.DOWNLOADED.name)
            }.map { it.toChapter() }
        }
    }

    override fun findNotDownloaded(seriesId: UUID): List<Chapter> {
        return transaction(database) {
            ChapterEntity.find {
                (ChapterTable.seriesId eq seriesId) and
                (ChapterTable.downloadStatus eq DownloadStatus.NOT_DOWNLOADED.name)
            }.map { it.toChapter() }
        }
    }

    override fun save(metadata: ChapterMetadata, seriesId: UUID): Chapter {
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

            entity.toChapter()
        }
    }

    override fun saveAll(chapters: List<ChapterMetadata>, seriesId: UUID): List<Chapter> {
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

                entity.toChapter()
            }
        }
    }

    override fun markDownloading(chapterId: UUID) {
        transaction(database) {
            val entity = ChapterEntity.findById(chapterId)
                ?: throw IllegalArgumentException("Chapter not found: $chapterId")
            entity.downloadStatus = DownloadStatus.DOWNLOADING.name
            entity.updatedAt = Instant.now()
        }
    }

    override fun markDownloaded(chapterId: UUID, filePath: String, fileSize: Long, storageFormat: String) {
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

    override fun markFailed(chapterId: UUID) {
        transaction(database) {
            val entity = ChapterEntity.findById(chapterId)
                ?: throw IllegalArgumentException("Chapter not found: $chapterId")
            entity.downloadStatus = DownloadStatus.FAILED.name
            entity.updatedAt = Instant.now()
        }
    }

    override fun resetDownloadStatus(chapterId: UUID) {
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

    override fun delete(chapterId: UUID) {
        transaction(database) {
            ChapterEntity.findById(chapterId)?.delete()
        }
    }

    override fun deleteBySeriesId(seriesId: UUID) {
        transaction(database) {
            ChapterEntity.find { ChapterTable.seriesId eq seriesId }.forEach { it.delete() }
        }
    }

    override fun countBySeriesId(seriesId: UUID): Long {
        return transaction(database) {
            ChapterEntity.find { ChapterTable.seriesId eq seriesId }.count()
        }
    }

    override fun countDownloaded(seriesId: UUID): Long {
        return transaction(database) {
            ChapterEntity.find {
                (ChapterTable.seriesId eq seriesId) and
                (ChapterTable.downloadStatus eq DownloadStatus.DOWNLOADED.name)
            }.count()
        }
    }

    private fun ChapterEntity.toChapter(): Chapter {
        return Chapter(
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
