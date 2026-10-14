package dev.koenv.chaptervault.infrastructure.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        install(DefaultRequest) {
            headers.append("User-Agent", "ChapterVault/1.0")
        }

        install(HttpRequestRetry) { configureRetryPolicy() }
    }

private const val RATE_LIMIT_RETRY_AFTER = "X-RateLimit-Retry-After"

/** Longer than this, a 429 is returned instead of waited out: MangaDex bans IPs that keep sending after a 429. */
private const val MAX_RATE_LIMIT_WAIT_MS = 60_000L

/** [header] is MangaDex's X-RateLimit-Retry-After: the end of the rate-limit period as a UNIX timestamp in seconds. */
internal fun rateLimitWaitMs(
    header: String?,
    nowMs: Long = System.currentTimeMillis(),
): Long? = header?.toLongOrNull()?.let { (it * 1000 - nowMs).coerceAtLeast(0) }

internal fun HttpRequestRetryConfig.configureRetryPolicy() {
    maxRetries = 3
    retryIf { _, response ->
        when {
            response.status == HttpStatusCode.TooManyRequests ->
                (rateLimitWaitMs(response.headers[RATE_LIMIT_RETRY_AFTER]) ?: 0L) <= MAX_RATE_LIMIT_WAIT_MS
            else -> response.status.value in 500..599
        }
    }
    delayMillis(respectRetryAfterHeader = true) { retry ->
        val asked =
            response
                ?.takeIf { it.status == HttpStatusCode.TooManyRequests }
                ?.let { rateLimitWaitMs(it.headers[RATE_LIMIT_RETRY_AFTER]) }
        // a little slack after the period ends; otherwise 1 s, 2 s, 4 s
        asked?.plus(250L) ?: (1000L shl (retry - 1)).coerceAtMost(MAX_RATE_LIMIT_WAIT_MS)
    }
}
