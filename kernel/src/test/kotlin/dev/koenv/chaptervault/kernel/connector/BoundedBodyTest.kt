package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedBodyTest {
    private fun client(
        body: ByteArray,
        declaredLength: Long? = null,
    ) = HttpClient(
        MockEngine {
            val headers = if (declaredLength != null) headersOf(HttpHeaders.ContentLength, declaredLength.toString()) else headersOf()
            respond(content = body, status = HttpStatusCode.OK, headers = headers)
        },
    )

    @Test
    fun `a body within the limit is returned whole`() =
        runBlocking<Unit> {
            val bytes = ByteArray(1000) { it.toByte() }

            val read = client(bytes).get("http://x/a").readBytesBounded(1000)

            assertContentEquals(bytes, read)
        }

    @Test
    fun `a body over the limit is rejected`() =
        runBlocking<Unit> {
            assertFailsWith<ResponseTooLargeException> { client(ByteArray(1001)).get("http://x/a").readBytesBounded(1000) }
        }

    @Test
    fun `a declared length over the limit is rejected`() =
        runBlocking<Unit> {
            val response = client(ByteArray(2000), declaredLength = 2000L).get("http://x/a")

            assertFailsWith<ResponseTooLargeException> { response.readBytesBounded(1000) }
        }

    @Test
    fun `download fails cleanly instead of buffering an oversized page`() =
        runBlocking<Unit> {
            val context =
                DefaultConnectorContext(
                    client(ByteArray(5000)),
                    mapOf(Bucket.CDN to RateLimiter(requestsPerSecond = 100.0)),
                    maxDownloadBytes = 1000,
                )

            val result = context.download("http://x/page.jpg")

            assertIs<Result.Failure>(result)
            assertIs<AppError.InternalError>(result.error)
            assertTrue("larger than" in result.error.message, result.error.message)
        }

    @Test
    fun `download still returns a page within the limit`() =
        runBlocking<Unit> {
            val context =
                DefaultConnectorContext(
                    client(ByteArray(900)),
                    mapOf(Bucket.CDN to RateLimiter(requestsPerSecond = 100.0)),
                    maxDownloadBytes = 1000,
                )

            val result = context.download("http://x/page.jpg")

            assertIs<Result.Success<ByteArray>>(result)
            assertTrue(result.value.size == 900)
        }
}
