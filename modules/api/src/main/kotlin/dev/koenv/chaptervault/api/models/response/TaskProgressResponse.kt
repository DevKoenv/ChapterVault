package dev.koenv.chaptervault.api.models.response

import kotlinx.serialization.Serializable

@Serializable
data class TaskProgressResponse(
    val taskId: String,
    val status: String,
    val message: String,
    val current: Int,
    val total: Int,
    val error: String?
)
