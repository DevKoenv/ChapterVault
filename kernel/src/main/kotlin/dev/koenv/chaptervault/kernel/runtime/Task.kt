package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class Task(
    val id: Id,
    val type: TaskType,
    val status: TaskStatus,
    val targetType: TargetType,
    val targetId: Id,
    val payload: Map<String, String> = emptyMap(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    /** A task is not started before this moment (set on retries). Null means as soon as a worker is free. */
    val runAfter: Instant? = null,
)
