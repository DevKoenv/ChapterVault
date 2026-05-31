package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.connector.Bucket
import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

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
