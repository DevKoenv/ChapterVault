package dev.koenv.chaptervault.orchestration.task

/**
 * Status of a task
 */
enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Progress information for a task
 */
data class TaskProgress(
    val taskId: String,
    val status: TaskStatus,
    val message: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null
)
