package dev.koenv.chaptervault.api.models

import kotlinx.serialization.Serializable

/**
 * RFC 7807 Problem Details response format.
 * Provides standardized error responses across the API.
 */
@Serializable
data class ProblemDetail(
    /** URI reference identifying the problem type */
    val type: String,
    /** Short, human-readable summary */
    val title: String,
    /** HTTP status code */
    val status: Int,
    /** Human-readable explanation specific to this occurrence */
    val detail: String,
    /** URI reference identifying the specific occurrence */
    val instance: String,
    /** Field-level validation errors (for 400 responses) */
    val errors: List<FieldError> = emptyList()
)

/**
 * Field-level validation error.
 */
@Serializable
data class FieldError(
    /** Name of the field with the error */
    val field: String,
    /** Human-readable error message */
    val message: String,
    /** Machine-readable error code */
    val code: String
)

/**
 * Common error types as URI paths.
 */
object ErrorTypes {
    const val NOT_FOUND = "/errors/not-found"
    const val VALIDATION = "/errors/validation"
    const val CONFLICT = "/errors/conflict"
    const val CONNECTOR_UNAVAILABLE = "/errors/connector-unavailable"
    const val CONNECTOR_NOT_FOUND = "/errors/connector-not-found"
    const val TASK_FAILED = "/errors/task-failed"
    const val FILE_NOT_FOUND = "/errors/file-not-found"
    const val INSUFFICIENT_STORAGE = "/errors/insufficient-storage"
    const val INTERNAL_ERROR = "/errors/internal-error"
}
