package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

/**
 * DAO entity for Task
 */
class TaskEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TaskEntity>(TaskTable)

    var type by TaskTable.type
    var targetUrl by TaskTable.targetUrl
    var targetType by TaskTable.targetType
    var targetId by TaskTable.targetId
    var status by TaskTable.status
    var message by TaskTable.message
    var currentProgress by TaskTable.currentProgress
    var totalProgress by TaskTable.totalProgress
    var errorMessage by TaskTable.errorMessage
    var createdAt by TaskTable.createdAt
    var startedAt by TaskTable.startedAt
    var completedAt by TaskTable.completedAt
}
