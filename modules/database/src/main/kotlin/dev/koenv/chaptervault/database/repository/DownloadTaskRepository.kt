package dev.koenv.chaptervault.database.repository

import dev.koenv.chaptervault.core.repository.DownloadTaskRepositoryPort
import dev.koenv.chaptervault.core.repository.PersistedTask
import dev.koenv.chaptervault.core.repository.TaskStatus
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
 * Exposed-based implementation of DownloadTaskRepositoryPort.
 * Uses H2/SQLite/PostgreSQL via Exposed ORM.
 */
class DownloadTaskRepository(private val database: Database) : DownloadTaskRepositoryPort {

    override fun initialize() {
        transaction(database) {
            SchemaUtils.create(DownloadTaskTable)
        }
    }

    override fun findById(taskId: UUID): PersistedTask? {
        return transaction(database) {
            DownloadTaskEntity.findById(taskId)?.toPersistedTask()
        }
    }

    override fun findPending(): List<PersistedTask> {
        return transaction(database) {
            DownloadTaskEntity.find { DownloadTaskTable.status eq TaskStatus.PENDING.name }
                .map { it.toPersistedTask() }
        }
    }

    override fun findRunning(): List<PersistedTask> {
        return transaction(database) {
            DownloadTaskEntity.find { DownloadTaskTable.status eq TaskStatus.RUNNING.name }
                .map { it.toPersistedTask() }
        }
    }

    override fun findAll(status: TaskStatus?): List<PersistedTask> {
        return transaction(database) {
            val query = if (status != null) {
                DownloadTaskEntity.find { DownloadTaskTable.status eq status.name }
            } else {
                DownloadTaskEntity.all()
            }
            query.map { it.toPersistedTask() }
        }
    }

    override fun create(
        taskType: TaskType,
        targetUrl: String,
        seriesId: UUID?,
        chapterId: UUID?
    ): PersistedTask {
        return transaction(database) {
            val now = Instant.now()
            val entity = DownloadTaskEntity.new {
                this.taskType = taskType.name
                this.targetUrl = targetUrl
                this.series = seriesId?.let { SeriesEntity.findById(it) }
                this.chapter = chapterId?.let { ChapterEntity.findById(it) }
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
            val entity = DownloadTaskEntity.findById(taskId)
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
            val entity = DownloadTaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.RUNNING.name
            entity.startedAt = Instant.now()
            entity.totalProgress = totalProgress
        }
    }

    override fun markCompleted(taskId: UUID) {
        transaction(database) {
            val entity = DownloadTaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.COMPLETED.name
            entity.completedAt = Instant.now()
            entity.currentProgress = entity.totalProgress
        }
    }

    override fun markFailed(taskId: UUID, errorMessage: String?) {
        transaction(database) {
            val entity = DownloadTaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.FAILED.name
            entity.errorMessage = errorMessage
            entity.completedAt = Instant.now()
        }
    }

    override fun markCancelled(taskId: UUID) {
        transaction(database) {
            val entity = DownloadTaskEntity.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")
            entity.status = TaskStatus.CANCELLED.name
            entity.completedAt = Instant.now()
        }
    }

    override fun delete(taskId: UUID) {
        transaction(database) {
            DownloadTaskEntity.findById(taskId)?.delete()
        }
    }

    override fun cleanupOldTasks(olderThan: Instant) {
        transaction(database) {
            DownloadTaskEntity.find {
                (DownloadTaskTable.status eq TaskStatus.COMPLETED.name) or
                (DownloadTaskTable.status eq TaskStatus.FAILED.name) or
                (DownloadTaskTable.status eq TaskStatus.CANCELLED.name)
            }.filter {
                it.completedAt?.isBefore(olderThan) == true
            }.forEach {
                it.delete()
            }
        }
    }

    override fun resetRunningTasks() {
        transaction(database) {
            DownloadTaskEntity.find { DownloadTaskTable.status eq TaskStatus.RUNNING.name }
                .forEach {
                    it.status = TaskStatus.PENDING.name
                    it.startedAt = null
                }
        }
    }

    private fun DownloadTaskEntity.toPersistedTask(): PersistedTask {
        return PersistedTask(
            id = id.value,
            taskType = TaskType.valueOf(taskType),
            targetUrl = targetUrl,
            seriesId = series?.id?.value,
            chapterId = chapter?.id?.value,
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
