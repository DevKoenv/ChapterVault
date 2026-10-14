package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

// a page is one image; 25 MB is far above any real one and far below what could hurt the heap
const val DEFAULT_MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024

class DefaultConnectorContext(
    private val httpClient: HttpClient,
    private val buckets: Map<BucketKey, RateLimiter>,
    private val maxDownloadBytes: Int = DEFAULT_MAX_DOWNLOAD_BYTES,
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
            Result.Success(response.readBytesBounded(maxDownloadBytes))
        } catch (_: ResponseTooLargeException) {
            Result.Failure(AppError.InternalError("Download from $url is larger than the allowed $maxDownloadBytes bytes"))
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("HTTP download failed: ${e.message}", e))
        }
    }
}
