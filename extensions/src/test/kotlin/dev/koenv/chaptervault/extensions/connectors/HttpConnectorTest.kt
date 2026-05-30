package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpConnectorTest {
    private fun makeConnector(
        respondWith: HttpStatusCode = HttpStatusCode.OK,
        configs: Map<BucketKey, BucketConfig> = mapOf(Bucket.API to BucketConfig(requestsPerSecond = 10.0)),
    ): TestConnector {
        val engine = MockEngine { _ -> respond("", respondWith) }
        return object : HttpConnector(HttpClient(engine)), TestConnector {
            override val id = "test"
            override val name = "Test"
            override val bucketConfigs = configs

            override fun testContext() = context

            override suspend fun search(
                query: String,
                request: PageRequest,
            ): Result<Pagination<SeriesSearchResult>> = TODO()

            override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> = TODO()

            override suspend fun fetchChapters(
                externalId: String,
                language: String,
            ): Result<List<ChapterMetadata>> = TODO()

            override suspend fun download(
                chapter: Chapter,
                format: ChapterFormat,
            ): Result<DownloadResult> = TODO()
        }
    }

    private interface TestConnector : Connector {
        fun testContext(): ConnectorContext
    }

    @Test
    fun `context uses bucketConfigs to build rate limiters — configured bucket succeeds`() {
        val connector = makeConnector(HttpStatusCode.OK)

        val result =
            runBlocking {
                connector.testContext().get("https://example.com", bucket = Bucket.API)
            }
        assertIs<Result.Success<*>>(result)
    }

    @Test
    fun `context returns failure for unconfigured bucket`() {
        val connector = makeConnector()

        val result =
            runBlocking {
                connector.testContext().get("https://example.com", bucket = Bucket.CDN)
            }
        assertIs<Result.Failure>(result)
    }

    @Test
    fun `fetchPage calls context download with page url and headers — returns bytes on success`() {
        var capturedUrl: String? = null
        var capturedReferer: String? = null

        val engine =
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedReferer = request.headers["Referer"]
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
            }
        val connector =
            object : HttpConnector(HttpClient(engine)), TestConnector {
                override val id = "test"
                override val name = "Test"
                override val bucketConfigs: Map<BucketKey, BucketConfig> =
                    mapOf(
                        Bucket.CDN to BucketConfig(requestsPerSecond = 10.0),
                    )

                override fun testContext() = context

                override suspend fun search(
                    q: String,
                    r: PageRequest,
                ): Result<Pagination<SeriesSearchResult>> = TODO()

                override suspend fun fetchSeries(id: String): Result<SeriesMetadata> = TODO()

                override suspend fun fetchChapters(
                    id: String,
                    lang: String,
                ): Result<List<ChapterMetadata>> = TODO()

                override suspend fun download(
                    chapter: Chapter,
                    format: ChapterFormat,
                ): Result<DownloadResult> = TODO()
            }

        val page =
            DownloadPage(
                url = "https://cdn.example.com/p1.jpg",
                index = 0,
                headers = mapOf("Referer" to "https://example.com"),
            )

        val result = runBlocking { connector.fetchPage(page) }

        assertIs<Result.Success<ByteArray>>(result)
        assertEquals("https://cdn.example.com/p1.jpg", capturedUrl)
        assertEquals("https://example.com", capturedReferer)
    }
}
