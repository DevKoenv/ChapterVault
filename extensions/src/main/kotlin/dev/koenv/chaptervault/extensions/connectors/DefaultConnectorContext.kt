package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess

class DefaultConnectorContext(
    override val httpClient: HttpClient,
    private val buckets: Map<String, RateLimiter>,
) : ConnectorContext {

    private suspend fun bucket(name: String): RateLimiter? =
        buckets[name] ?: buckets["default"]

    override suspend fun get(
        url: String,
        params: Map<String, String>,
        bucket: String,
        headers: Map<String, String>,
    ): Result<HttpResponse> {
        val limiter = bucket(bucket)
            ?: return Result.Failure(AppError.InternalError("No rate limiter found for bucket '$bucket' and no 'default' bucket configured"))
        limiter.acquire()
        return try {
            val extraHeaders = headers
            val response = httpClient.get(url) {
                params.forEach { (k, v) -> parameter(k, v) }
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            if (!response.status.isSuccess()) {
                return Result.Failure(AppError.InternalError("HTTP ${response.status.value} from $url"))
            }
            Result.Success(response)
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("HTTP GET failed: ${e.message}", e))
        }
    }

    override suspend fun download(url: String, bucket: String, headers: Map<String, String>): Result<ByteArray> {
        val limiter = bucket(bucket)
            ?: return Result.Failure(AppError.InternalError("No rate limiter found for bucket '$bucket' and no 'default' bucket configured"))
        limiter.acquire()
        return try {
            val extraHeaders = headers
            val response = httpClient.get(url) {
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            if (!response.status.isSuccess()) {
                return Result.Failure(AppError.InternalError("HTTP ${response.status.value} from $url"))
            }
            Result.Success(response.readRawBytes())
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("HTTP download failed: ${e.message}", e))
        }
    }
}
