package dev.chaptervault.kernel.runtime

import dev.chaptervault.shared.utils.Id
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
)
