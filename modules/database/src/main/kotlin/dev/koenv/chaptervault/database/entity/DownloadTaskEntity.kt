package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

/**
 * DAO entity for DownloadTask
 */
class DownloadTaskEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<DownloadTaskEntity>(DownloadTaskTable)

    var taskType by DownloadTaskTable.taskType
    var targetUrl by DownloadTaskTable.targetUrl
    var series by SeriesEntity optionalReferencedOn DownloadTaskTable.seriesId
    var chapter by ChapterEntity optionalReferencedOn DownloadTaskTable.chapterId
    var status by DownloadTaskTable.status
    var message by DownloadTaskTable.message
    var currentProgress by DownloadTaskTable.currentProgress
    var totalProgress by DownloadTaskTable.totalProgress
    var errorMessage by DownloadTaskTable.errorMessage
    var createdAt by DownloadTaskTable.createdAt
    var startedAt by DownloadTaskTable.startedAt
    var completedAt by DownloadTaskTable.completedAt
}
