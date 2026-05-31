package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.connector.BucketKey
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
    private val httpClient: HttpClient,
    private val buckets: Map<BucketKey, RateLimiter>,
) : ConnectorContext {
    override suspend fun get(
        url: String,
        params: Map<String, String>,
        bucket: BucketKey,
        headers: Map<String, String>,
    ): Result<HttpResponse> {
        val limiter =
            buckets[bucket]
                ?: return Result.Failure(AppError.InternalError("No rate limiter configured for bucket '${bucket.id}'"))
        limiter.acquire()
        return try {
            val response =
                httpClient.get(url) {
                    params.forEach { (k, v) -> parameter(k, v) }
                    headers.forEach { (k, v) -> header(k, v) }
                }
            if (!response.status.isSuccess()) {
                return Result.Failure(AppError.InternalError("HTTP ${response.status.value} from $url"))
            }
            Result.Success(response)
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("HTTP GET failed: ${e.message}", e))
        }
    }

    override suspend fun download(
        url: String,
        bucket: BucketKey,
        headers: Map<String, String>,
    ): Result<ByteArray> {
        val limiter =
            buckets[bucket]
                ?: return Result.Failure(AppError.InternalError("No rate limiter configured for bucket '${bucket.id}'"))
        limiter.acquire()
        return try {
            val response =
                httpClient.get(url) {
                    headers.forEach { (k, v) -> header(k, v) }
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
