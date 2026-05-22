package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class TaskDto(
    val id: String,
    val type: String,
    val status: String,
    val targetType: String,
    val targetId: String,
    val createdAt: String,
    val updatedAt: String,
    val payload: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    val retryCount: Int = 0,
)
