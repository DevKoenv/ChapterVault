package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultConnectorContextTest {
    private val httpClient =
        HttpClient(
            MockEngine { _ ->
                error("HTTP client should not be called in this test")
            },
        )

    @Test
    fun `get returns failure when bucket key is not configured`() {
        val context =
            DefaultConnectorContext(
                httpClient,
                mapOf(Bucket.API to RateLimiter(requestsPerSecond = 100.0)),
            )
        val result =
            runBlocking {
                context.get("https://example.com", bucket = Bucket.CDN)
            }
        assertIs<Result.Failure>(result)
    }

    private fun contextAnswering(status: HttpStatusCode) =
        DefaultConnectorContext(
            HttpClient(MockEngine { respond("", status) }),
            mapOf(Bucket.API to RateLimiter(requestsPerSecond = 100.0), Bucket.CDN to RateLimiter(requestsPerSecond = 100.0)),
        )

    @Test
    fun `a missing upstream resource is reported as not found, a server error is not`() {
        runBlocking {
            for (gone in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone)) {
                val ctx = contextAnswering(gone)
                assertIs<AppError.NotFound>((ctx.get("https://example.com/a") as Result.Failure).error)
                assertIs<AppError.NotFound>((ctx.download("https://example.com/a", bucket = Bucket.CDN) as Result.Failure).error)
            }
            val broken = contextAnswering(HttpStatusCode.InternalServerError)
            assertIs<AppError.InternalError>((broken.get("https://example.com/a") as Result.Failure).error)
        }
    }

    @Test
    fun `a relative endpoint is resolved against the connector's base URL`() {
        var capturedUrl = ""
        val context =
            DefaultConnectorContext(
                HttpClient(
                    MockEngine { request ->
                        capturedUrl = request.url.toString()
                        respond("", HttpStatusCode.OK)
                    },
                ),
                mapOf(Bucket.API to RateLimiter(requestsPerSecond = 100.0)),
                baseUrl = "https://api.example.com",
            )

        runBlocking { context.get("/manga/1") }

        assertEquals("https://api.example.com/manga/1", capturedUrl)
    }

    @Test
    fun `an absolute endpoint bypasses the connector's base URL`() {
        var capturedUrl = ""
        val context =
            DefaultConnectorContext(
                HttpClient(
                    MockEngine { request ->
                        capturedUrl = request.url.toString()
                        respond("", HttpStatusCode.OK)
                    },
                ),
                mapOf(Bucket.API to RateLimiter(requestsPerSecond = 100.0)),
                baseUrl = "https://api.example.com",
            )

        runBlocking { context.get("https://other.example.com/x") }

        assertEquals("https://other.example.com/x", capturedUrl)
    }

    @Test
    fun `a relative endpoint without a configured base URL fails fast`() {
        val context =
            DefaultConnectorContext(
                httpClient,
                mapOf(Bucket.API to RateLimiter(requestsPerSecond = 100.0)),
            )

        val result = runBlocking { context.get("/manga/1") }

        assertIs<Result.Failure>(result)
        assertIs<AppError.InternalError>(result.error)
    }

    @Test
    fun `query params are appended to the resolved URL without double-encoding`() {
        var capturedUrl = ""
        val context =
            DefaultConnectorContext(
                HttpClient(
                    MockEngine { request ->
                        capturedUrl = request.url.toString()
                        respond("", HttpStatusCode.OK)
                    },
                ),
                mapOf(Bucket.API to RateLimiter(requestsPerSecond = 100.0)),
                baseUrl = "https://api.example.com",
            )

        runBlocking {
            context.get(
                "/manga",
                params = QueryParams.of("keyword" to "One Piece", "content_rating" to listOf("safe", "suggestive")),
            )
        }

        val parsed = Url(capturedUrl)
        assertEquals("One Piece", parsed.parameters["keyword"])
        assertEquals(listOf("safe", "suggestive"), parsed.parameters.getAll("content_rating[]"))
        assertTrue(!capturedUrl.contains("%255B"), "brackets should not be percent-encoded twice: $capturedUrl")
    }

    @Test
    fun `download returns failure when bucket key is not configured`() {
        val context =
            DefaultConnectorContext(
                httpClient,
                mapOf(Bucket.CDN to RateLimiter(requestsPerSecond = 100.0)),
            )
        val result =
            runBlocking {
                context.download("https://example.com/image.jpg", bucket = Bucket.API)
            }
        assertIs<Result.Failure>(result)
    }
}
