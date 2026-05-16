package dev.koenv.chaptervault.shared.result

sealed class AppError(open val message: String) {
    data class NotFound(val resource: String, val id: String) : AppError("$resource '$id' not found")
    data class ValidationError(override val message: String) : AppError(message)
    data class Conflict(override val message: String) : AppError(message)
    data class InternalError(override val message: String, val cause: Throwable? = null) : AppError(message)
    data class Unauthorized(override val message: String = "Unauthorized") : AppError(message)
    data class Forbidden(override val message: String = "Forbidden") : AppError(message)
}
