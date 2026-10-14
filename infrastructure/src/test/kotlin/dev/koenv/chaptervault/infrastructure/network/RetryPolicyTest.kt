package dev.koenv.chaptervault.infrastructure.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryPolicyTest {
    @Test
    fun `the wait comes from X-RateLimit-Retry-After, a UNIX timestamp in seconds`() {
        assertEquals(4_000L, rateLimitWaitMs("1004", nowMs = 1_000_000L))
        assertEquals(0L, rateLimitWaitMs("990", nowMs = 1_000_000L))
        assertNull(rateLimitWaitMs(null, nowMs = 1_000_000L))
        assertNull(rateLimitWaitMs("soon", nowMs = 1_000_000L))
    }

    @Test
    fun `a 429 that asks for a long wait is not retried`() {
        var calls = 0
        val farFuture = (System.currentTimeMillis() / 1000 + 3_600).toString()
        val client =
            HttpClient(
                MockEngine {
                    calls++
                    respond("", HttpStatusCode.TooManyRequests, headersOf("X-RateLimit-Retry-After", farFuture))
                },
            ) { install(HttpRequestRetry) { configureRetryPolicy() } }

        val status = runBlocking { client.get("https://api.mangadex.org/x").status }

        assertEquals(HttpStatusCode.TooManyRequests, status)
        assertEquals(1, calls, "persisting after a 429 can get the IP banned")
    }

    @Test
    fun `a server error is still retried`() {
        var calls = 0
        val client =
            HttpClient(
                MockEngine {
                    calls++
                    if (calls == 1) respond("", HttpStatusCode.BadGateway) else respond("ok", HttpStatusCode.OK)
                },
            ) { install(HttpRequestRetry) { configureRetryPolicy() } }

        val status = runBlocking { client.get("https://example.com/x").status }

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(2, calls)
    }
}
