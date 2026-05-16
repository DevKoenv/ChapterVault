package dev.koenv.chaptervault.kernel.runtime

enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
