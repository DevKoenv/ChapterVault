package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess

// a page is one image; 25 MB is far above any real one and far below what could hurt the heap
const val DEFAULT_MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024

class DefaultConnectorContext(
    private val httpClient: HttpClient,
    private val buckets: Map<BucketKey, RateLimiter>,
    private val maxDownloadBytes: Int = DEFAULT_MAX_DOWNLOAD_BYTES,
    private val baseUrl: String? = null,
) : ConnectorContext {
    override suspend fun get(
        url: String,
        params: QueryParams,
        bucket: BucketKey,
        headers: Map<String, String>,
    ): Result<HttpResponse> {
        val limiter =
            buckets[bucket]
                ?: return Result.Failure(AppError.InternalError("No rate limiter configured for bucket '${bucket.id}'"))
        limiter.acquire()
        return try {
            val requestUrl = resolveUrl(baseUrl, url, params)
            val response =
                httpClient.get(requestUrl) {
                    headers.forEach { (k, v) -> header(k, v) }
                }
            if (!response.status.isSuccess()) {
                return Result.Failure(httpFailure(response.status, requestUrl))
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
                return Result.Failure(httpFailure(response.status, url))
            }
            Result.Success(response.readBytesBounded(maxDownloadBytes))
        } catch (_: ResponseTooLargeException) {
            Result.Failure(AppError.InternalError("Download from $url is larger than the allowed $maxDownloadBytes bytes"))
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("HTTP download failed: ${e.message}", e))
        }
    }

    /** 404 and 410 will not change on a retry, so they are not reported like a failing server. */
    private fun httpFailure(
        status: HttpStatusCode,
        url: String,
    ): AppError =
        if (status == HttpStatusCode.NotFound || status == HttpStatusCode.Gone) {
            AppError.NotFound("Upstream resource", url)
        } else {
            AppError.InternalError("HTTP ${status.value} from $url")
        }
}

/**
 * [endpoint] is used as-is when it is already absolute; otherwise it is resolved against
 * [baseUrl]. [params] is appended as the query string either way, via Ktor's own encoding.
 */
internal fun resolveUrl(
    baseUrl: String?,
    endpoint: String,
    params: QueryParams,
): String {
    val absolute = endpoint.startsWith("http://", ignoreCase = true) || endpoint.startsWith("https://", ignoreCase = true)
    val target =
        if (absolute) {
            endpoint
        } else {
            requireNotNull(baseUrl) { "'$endpoint' is a relative endpoint, but this connector has no base URL" }
            baseUrl.trimEnd('/') + "/" + endpoint.trimStart('/')
        }
    return URLBuilder(target)
        .apply { params.flatten().forEach { (k, v) -> parameters.append(k, v) } }
        .buildString()
}
