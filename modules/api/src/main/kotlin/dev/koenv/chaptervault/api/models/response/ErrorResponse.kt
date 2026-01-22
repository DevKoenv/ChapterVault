package dev.koenv.chaptervault.api.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)
