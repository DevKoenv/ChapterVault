package dev.koenv.chaptervault.database.repository

import dev.koenv.chaptervault.core.repository.PersistedTask
import dev.koenv.chaptervault.core.repository.TaskStatus
import dev.koenv.chaptervault.core.repository.TaskTargetType
import dev.koenv.chaptervault.core.repository.TaskRepositoryPort
import dev.koenv.chaptervault.core.repository.TaskType
import dev.koenv.chaptervault.database.entity.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Exposed-based implementation of TaskRepositoryPort.
 * Uses H2/SQLite/PostgreSQL via Exposed ORM.
 * Tasks reference domain entities by type+id string (no FK) for decoupling.
 */
class TaskRepository(private val database: Database) : TaskRepositoryPort {

    override fun initialize() {
        transaction(database) {
            SchemaUtils.create(TaskTable)
        }
    }

    override fun findById(taskId: UUID): PersistedTask? {
        return transaction(database) {
            TaskEntity.findById(taskId)?.toPersistedTask()
        }
    }

    override fun findPending(): List<PersistedTask> {
        return transaction(database) {
            TaskEntity.find { TaskTable.status eq TaskStatus.PENDING.name }
                .map { it.toPersistedTask() }
        }
    }

    override fun findRunning(): List<PersistedTask> {
        return transaction(database) {
            TaskEntity.find { TaskTable.status eq TaskStatus.RUNNING.name }
                .map { it.toPersistedTask() }
        }
    }

    override fun findAll(status: TaskStatus?): List<PersistedTask> {
        return transaction(database) {
            val query = if (status != null) {
                TaskEntity.find { TaskTable.status eq status.name }
            } else {
                TaskEntity.all()
            }
            query.map { it.toPersistedTask() }
        }
    }

    override fun create(
        type: TaskType,
        targetUrl: String,
        targetType: TaskTargetType,
        targetId: UUID?
    ): PersistedTask {
        return transaction(database) {
            val now = Instant.now()
            val entity = TaskEntity.new {
                this.type = type.name
                this.targetUrl = targetUrl
                this.targetType = targetType.name
                this.targetId = targetId?.toString()
                this.status = TaskStatus.PENDING.name
                this.currentProgress = 0
                this.totalProgress = 0
                this.createdAt = now
            }
            entity.toPersistedTask()
        }
    }

    override fun updateProgress(
        taskId: UUID,
        status: TaskStatus,
        message: String?,
        current: Int?,
        total: Int?
    ) {
        transaction(database) {
            val entity = TaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")

            entity.status = status.name
            if (message != null) entity.message = message
            if (current != null) entity.currentProgress = current
            if (total != null) entity.totalProgress = total

            if (status == TaskStatus.RUNNING && entity.startedAt == null) {
                entity.startedAt = Instant.now()
            }
        }
    }

    override fun markStarted(taskId: UUID, totalProgress: Int) {
        transaction(database) {
            val entity = TaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.RUNNING.name
            entity.startedAt = Instant.now()
            entity.totalProgress = totalProgress
        }
    }

    override fun markCompleted(taskId: UUID) {
        transaction(database) {
            val entity = TaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.COMPLETED.name
            entity.completedAt = Instant.now()
            entity.currentProgress = entity.totalProgress
        }
    }

    override fun markFailed(taskId: UUID, errorMessage: String?) {
        transaction(database) {
            val entity = TaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.FAILED.name
            entity.errorMessage = errorMessage
            entity.completedAt = Instant.now()
        }
    }

    override fun markCancelled(taskId: UUID) {
        transaction(database) {
            val entity = TaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.CANCELLED.name
            entity.completedAt = Instant.now()
        }
    }

    override fun delete(taskId: UUID) {
        transaction(database) {
            TaskEntity.findById(taskId)?.delete()
        }
    }

    override fun cleanupOldTasks(olderThan: Instant) {
        transaction(database) {
            TaskEntity.find {
                (TaskTable.status eq TaskStatus.COMPLETED.name) or
                (TaskTable.status eq TaskStatus.FAILED.name) or
                (TaskTable.status eq TaskStatus.CANCELLED.name)
            }.filter {
                it.completedAt?.isBefore(olderThan) == true
            }.forEach {
                it.delete()
            }
        }
    }

    override fun resetRunningTasks() {
        transaction(database) {
            TaskEntity.find { TaskTable.status eq TaskStatus.RUNNING.name }
                .forEach {
                    it.status = TaskStatus.PENDING.name
                    it.startedAt = null
                }
        }
    }

    private fun TaskEntity.toPersistedTask(): PersistedTask {
        return PersistedTask(
            id = id.value,
            taskType = TaskType.valueOf(type),
            targetUrl = targetUrl,
            targetType = TaskTargetType.valueOf(targetType),
            targetId = targetId?.let { UUID.fromString(it) },
            status = TaskStatus.valueOf(status),
            message = message,
            currentProgress = currentProgress,
            totalProgress = totalProgress,
            errorMessage = errorMessage,
            createdAt = createdAt,
            startedAt = startedAt,
            completedAt = completedAt
        )
    }
}
