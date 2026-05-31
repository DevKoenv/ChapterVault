package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.connector.Bucket
import dev.koenv.chaptervault.kernel.connector.BucketKey
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@PublishedApi
internal val lenientJson = Json { ignoreUnknownKeys = true }

suspend inline fun <reified T> ConnectorContext.getJson(
    url: String,
    params: Map<String, String> = emptyMap(),
    bucket: BucketKey = Bucket.API,
    headers: Map<String, String> = emptyMap(),
): Result<T> {
    val response =
        when (val r = get(url, params, bucket, headers)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
    return try {
        Result.Success(lenientJson.decodeFromString<T>(response.bodyAsText()))
    } catch (e: Exception) {
        Result.Failure(AppError.InternalError("Failed to parse JSON response from $url: ${e.message}", e))
    }
}

suspend fun ConnectorContext.getDocument(
    url: String,
    params: Map<String, String> = emptyMap(),
    bucket: BucketKey = Bucket.API,
    headers: Map<String, String> = emptyMap(),
): Result<Document> {
    val response =
        when (val r = get(url, params, bucket, headers)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
    return try {
        Result.Success(Jsoup.parse(response.bodyAsText(), url))
    } catch (e: Exception) {
        Result.Failure(AppError.InternalError("Failed to parse HTML from $url: ${e.message}", e))
    }
}
