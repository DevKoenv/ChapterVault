package dev.koenv.chaptervault.infrastructure.connectors

import dev.koenv.chaptervault.sdk.connector.getJson
import dev.koenv.chaptervault.sdk.connector.HttpConnector
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
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class ApiSearchResponse(
    val results: List<ApiSeriesItem>,
    val total: Int,
)

@Serializable
private data class ApiSeriesItem(
    val id: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
)

@Serializable
private data class ApiSeriesDetail(
    val id: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
)

@Serializable
private data class ApiChapterItem(
    val id: String,
    val title: String,
    @SerialName("chapter_number") val chapterNumber: Double,
    @SerialName("page_count") val pageCount: Int? = null,
)

@Serializable
private data class ApiChapterPages(
    val pages: List<String>,
)

class CustomConnector(
    httpClient: HttpClient,
    // TODO: replace with your site's base URL
    private val baseUrl: String = "https://your-site.example.com",
) : HttpConnector(httpClient) {
    // TODO: change id and name to match your site
    override val id: String = "custom"
    override val name: String = "Custom Connector"

    override val bucketConfigs: Map<BucketKey, BucketConfig> =
        mapOf(
            Bucket.API to BucketConfig(requestsPerSecond = 2.0),
            Bucket.CDN to BucketConfig(requestsPerSecond = 5.0),
        )

    override suspend fun search(
        query: String,
        request: PageRequest,
    ): Result<Pagination<SeriesSearchResult>> {
        // TODO: adjust endpoint path and params for your search API
        val response =
            context.getJson<ApiSearchResponse>(
                url = "$baseUrl/api/search",
                params =
                    mapOf(
                        "q" to query,
                        "page" to request.page.toString(),
                        "limit" to request.size.toString(),
                    ),
            )
        return when (response) {
            is Result.Failure -> response
            is Result.Success -> {
                val data = response.value
                Result.Success(
                    Pagination(
                        items = data.results.map { it.toSearchResult() },
                        page = request.page,
                        size = request.size,
                        totalItems = data.total.toLong(),
                    ),
                )
            }
        }
    }

    override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> {
        // TODO: adjust endpoint path
        val response =
            context.getJson<ApiSeriesDetail>(
                url = "$baseUrl/api/series/$externalId",
            )
        return when (response) {
            is Result.Failure -> response
            is Result.Success -> Result.Success(response.value.toMetadata())
        }
    }

    override suspend fun fetchChapters(
        externalId: String,
        language: String,
    ): Result<List<ChapterMetadata>> {
        // TODO: adjust endpoint path
        val response =
            context.getJson<List<ApiChapterItem>>(
                url = "$baseUrl/api/series/$externalId/chapters",
            )
        return when (response) {
            is Result.Failure -> response
            is Result.Success -> Result.Success(response.value.map { it.toMetadata() })
        }
    }

    override suspend fun download(
        chapter: Chapter,
        format: ChapterFormat,
    ): Result<DownloadResult> {
        // TODO: adjust endpoint path, chapter.externalId comes from fetchChapters
        val response =
            context.getJson<ApiChapterPages>(
                url = "$baseUrl/api/chapters/${chapter.externalId}/pages",
                bucket = Bucket.CDN,
            )
        return when (response) {
            is Result.Failure -> response
            is Result.Success -> {
                val pageUrls = response.value.pages
                if (pageUrls.isEmpty()) {
                    return Result.Failure(AppError.InternalError("No pages returned for chapter ${chapter.externalId}"))
                }
                Result.Success(DownloadResult(pages = pageUrls.mapIndexed { i, url -> DownloadPage(url = url, index = i) }))
            }
        }
    }

    private fun ApiSeriesItem.toSearchResult() =
        SeriesSearchResult(
            externalId = id,
            title = title,
            coverUrl = coverUrl,
            description = description,
        )

    private fun ApiSeriesDetail.toMetadata() =
        SeriesMetadata(
            externalId = id,
            title = title,
            coverUrl = coverUrl,
            description = description,
        )

    private fun ApiChapterItem.toMetadata() =
        ChapterMetadata(
            externalId = id,
            title = title,
            chapterIndex = chapterNumber,
            pageCount = pageCount,
        )
}
