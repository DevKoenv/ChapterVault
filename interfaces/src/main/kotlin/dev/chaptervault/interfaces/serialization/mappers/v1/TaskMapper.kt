package dev.chaptervault.interfaces.serialization.mappers.v1

import dev.chaptervault.interfaces.serialization.dto.v1.TaskDto
import dev.chaptervault.kernel.runtime.Task

fun Task.toDto(): TaskDto = TaskDto(
    id = id.toString(),
    type = type.name,
    status = status.name,
    targetType = targetType.name,
    targetId = targetId.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    errorMessage = errorMessage,
)
