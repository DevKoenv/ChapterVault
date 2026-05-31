package dev.koenv.chaptervault.infrastructure.connectors

import dev.koenv.chaptervault.extensions.connectors.HttpConnector
import dev.koenv.chaptervault.kernel.connector.Bucket
import dev.koenv.chaptervault.kernel.connector.BucketConfig
import dev.koenv.chaptervault.kernel.connector.BucketKey
import dev.koenv.chaptervault.kernel.connector.ChapterMetadata
import dev.koenv.chaptervault.kernel.connector.DownloadPage
import dev.koenv.chaptervault.kernel.connector.DownloadResult
import dev.koenv.chaptervault.kernel.connector.SeriesMetadata
import dev.koenv.chaptervault.kernel.connector.SeriesSearchResult
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient

class MockConnector : HttpConnector(HttpClient()) {
    override val id: String = "mock"
    override val name: String = "Mock Connector"

    override val bucketConfigs: Map<BucketKey, BucketConfig> =
        mapOf(
            Bucket.CDN to BucketConfig(requestsPerSecond = 100.0),
        )

    // returns mock bytes directly, skips HTTP and rate limiting
    override suspend fun fetchPage(page: DownloadPage): Result<ByteArray> = Result.Success(ByteArray(10) { i -> i.toByte() })

    override suspend fun search(
        query: String,
        request: PageRequest,
    ): Result<Pagination<SeriesSearchResult>> {
        val allResults =
            when {
                query.contains("piece", ignoreCase = true) ->
                    listOf(
                        SeriesSearchResult("mock-one-piece", "One Piece", null, "A pirate adventure"),
                        SeriesSearchResult("mock-naruto", "Naruto", null, "A ninja adventure"),
                    )

                query.isBlank() ->
                    listOf(
                        SeriesSearchResult("mock-001", "Alpha", null, null),
                        SeriesSearchResult("mock-002", "Beta", null, null),
                        SeriesSearchResult("mock-003", "Gamma", null, null),
                    )

                else ->
                    listOf(
                        SeriesSearchResult("mock-${query.take(8)}", "Mock: $query", null, null),
                    )
            }

        val skip = request.page * request.size
        val take = request.size
        val paginatedItems = allResults.drop(skip).take(take)

        return Result.Success(
            Pagination(
                items = paginatedItems,
                page = request.page,
                size = request.size,
                totalItems = allResults.size.toLong(),
            ),
        )
    }

    override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> =
        Result.Success(SeriesMetadata(externalId = externalId, title = "Mock: $externalId", description = "Auto-generated mock series"))

    override suspend fun fetchChapters(
        externalId: String,
        language: String,
    ): Result<List<ChapterMetadata>> =
        Result.Success(
            listOf(
                ChapterMetadata("$externalId-ch1", "Chapter 1", 1.0, 12),
                ChapterMetadata("$externalId-ch2", "Chapter 2", 2.0, 18),
                ChapterMetadata("$externalId-ch3", "Chapter 3", 3.0, 24),
            ),
        )

    override suspend fun download(
        chapter: Chapter,
        format: ChapterFormat,
    ): Result<DownloadResult> =
        Result.Success(
            DownloadResult(
                pages = List(3) { i -> DownloadPage(url = "https://mock.example.com/page/$i", index = i) },
            ),
        )
}
