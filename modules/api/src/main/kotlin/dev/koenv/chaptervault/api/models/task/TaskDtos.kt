package dev.koenv.chaptervault.api.models.task

import kotlinx.serialization.Serializable

@Serializable
data class TaskProgressDto(
    val current: Int,
    val total: Int,
    val percentage: Int
)

@Serializable
data class TaskStatusResponse(
    val id: String,
    val taskType: String,
    val targetUrl: String,
    val targetType: String,
    val targetId: String?,
    val status: String,
    val message: String?,
    val progress: TaskProgressDto,
    val error: String?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?
)

@Serializable
data class TaskListResponse(
    val tasks: List<TaskStatusResponse>
)

@Serializable
data class TaskCreatedResponse(
    val taskId: String,
    val status: String,
    val message: String
)
