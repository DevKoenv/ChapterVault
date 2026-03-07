package dev.koenv.chaptervault.core.repository

import java.time.Instant
import java.util.UUID

/**
 * Port interface for task repository.
 * Implementations can use different database backends (H2, SQLite, PostgreSQL, etc.)
 */
interface TaskRepositoryPort {

    /**
     * Initialize the repository (create tables, etc.)
     */
    fun initialize()

    /**
     * Find task by ID
     */
    fun findById(taskId: UUID): PersistedTask?

    /**
     * Find all pending tasks
     */
    fun findPending(): List<PersistedTask>

    /**
     * Find all running tasks
     */
    fun findRunning(): List<PersistedTask>

    /**
     * Find all tasks (optionally filtered by status)
     */
    fun findAll(status: TaskStatus? = null): List<PersistedTask>

    /**
     * Create a new task.
     * @param targetType The type of entity this task targets
     * @param targetId The ID of the target entity (no FK constraint)
     */
    fun create(
        type: TaskType,
        targetUrl: String,
        targetType: TaskTargetType,
        targetId: UUID? = null
    ): PersistedTask

    /**
     * Update task status and progress
     */
    fun updateProgress(
        taskId: UUID,
        status: TaskStatus,
        message: String? = null,
        current: Int? = null,
        total: Int? = null
    )

    /**
     * Mark task as started
     */
    fun markStarted(taskId: UUID, totalProgress: Int = 0)

    /**
     * Mark task as completed
     */
    fun markCompleted(taskId: UUID)

    /**
     * Mark task as failed
     */
    fun markFailed(taskId: UUID, errorMessage: String?)

    /**
     * Mark task as cancelled
     */
    fun markCancelled(taskId: UUID)

    /**
     * Delete a task
     */
    fun delete(taskId: UUID)

    /**
     * Cleanup old completed/failed/cancelled tasks
     */
    fun cleanupOldTasks(olderThan: Instant)

    /**
     * Reset running tasks to pending (for recovery after crash)
     */
    fun resetRunningTasks()
}
