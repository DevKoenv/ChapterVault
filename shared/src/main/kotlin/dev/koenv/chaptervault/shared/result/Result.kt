package dev.koenv.chaptervault.shared.result

sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure(val error: AppError) : Result<Nothing>()
}

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Failure -> this
}

inline fun <T> Result<T>.getOrElse(default: (AppError) -> T): T = when (this) {
    is Result.Success -> value
    is Result.Failure -> default(error)
}
