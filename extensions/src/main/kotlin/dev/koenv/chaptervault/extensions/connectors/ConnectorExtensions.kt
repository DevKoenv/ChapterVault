package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

suspend inline fun <reified T> ConnectorContext.getJson(
    url: String,
    params: Map<String, String> = emptyMap(),
    bucket: String = "api",
): Result<T> {
    val response = when (val r = get(url, params, bucket)) {
        is Result.Failure -> return r
        is Result.Success -> r.value
    }
    return try {
        Result.Success(Json.decodeFromString<T>(response.bodyAsText()))
    } catch (e: Exception) {
        Result.Failure(AppError.InternalError("Failed to parse JSON response from $url: ${e.message}", e))
    }
}
