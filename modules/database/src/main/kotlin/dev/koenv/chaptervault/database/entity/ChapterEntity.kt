package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

/**
 * DAO entity for Chapter
 */
class ChapterEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ChapterEntity>(ChapterTable)

    var series by SeriesEntity referencedOn ChapterTable.seriesId
    var connector by ChapterTable.connector
    var externalId by ChapterTable.externalId
    var sourceUrl by ChapterTable.sourceUrl
    var title by ChapterTable.title
    var chapterNumber by ChapterTable.chapterNumber
    var chapterIndex by ChapterTable.chapterIndex
    var publishDate by ChapterTable.publishDate
    var pageCount by ChapterTable.pageCount
    var downloadStatus by ChapterTable.downloadStatus
    var downloadedAt by ChapterTable.downloadedAt
    var filePath by ChapterTable.filePath
    var fileSize by ChapterTable.fileSize
    var storageFormat by ChapterTable.storageFormat
    var createdAt by ChapterTable.createdAt
    var updatedAt by ChapterTable.updatedAt
}
