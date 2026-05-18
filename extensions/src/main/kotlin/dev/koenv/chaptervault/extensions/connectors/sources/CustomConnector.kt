package dev.koenv.chaptervault.extensions.connectors.sources

import dev.koenv.chaptervault.extensions.connectors.ChapterMetadata
import dev.koenv.chaptervault.extensions.connectors.Connector
import dev.koenv.chaptervault.extensions.connectors.ConnectorContext
import dev.koenv.chaptervault.extensions.connectors.DownloadResult
import dev.koenv.chaptervault.extensions.connectors.SeriesMetadata
import dev.koenv.chaptervault.extensions.connectors.SeriesSearchResult
import dev.koenv.chaptervault.extensions.connectors.getJson
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Customize: change these to match your site's JSON response shapes ──────────

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

// ── End customize ────────────────────────────────────────────────────────────────

class CustomConnector(
    private val context: ConnectorContext,
    // Customize: replace with your site's base URL
    private val baseUrl: String = "https://your-site.example.com",
) : Connector {

    // Customize: change these to match your site's connector ID and display name
    override val id: String = "custom"
    override val name: String = "Custom Connector"

    override suspend fun search(query: String, request: PageRequest): Result<Pagination<SeriesSearchResult>> {
        // Customize: adjust the endpoint path and parameter names to match your site's search API
        val response = context.getJson<ApiSearchResponse>(
            url = "$baseUrl/api/search",
            params = mapOf(
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
                    )
                )
            }
        }
    }

    override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> {
        // Customize: adjust the endpoint path
        val response = context.getJson<ApiSeriesDetail>(
            url = "$baseUrl/api/series/$externalId",
        )
        return when (response) {
            is Result.Failure -> response
            is Result.Success -> Result.Success(response.value.toMetadata())
        }
    }

    override suspend fun fetchChapters(externalId: String, language: String): Result<List<ChapterMetadata>> {
        // Customize: adjust the endpoint path
        val response = context.getJson<List<ApiChapterItem>>(
            url = "$baseUrl/api/series/$externalId/chapters",
        )
        return when (response) {
            is Result.Failure -> response
            is Result.Success -> Result.Success(response.value.map { it.toMetadata() })
        }
    }

    override suspend fun download(chapter: Chapter, format: ChapterFormat): Result<DownloadResult> {
        // Customize: adjust the endpoint path; chapter.externalId is the ID from fetchChapters
        val response = context.getJson<ApiChapterPages>(
            url = "$baseUrl/api/chapters/${chapter.externalId}/pages",
            bucket = "cdn",
        )
        return when (response) {
            is Result.Failure -> response
            is Result.Success -> {
                val pages = response.value.pages
                if (pages.isEmpty()) {
                    return Result.Failure(AppError.InternalError("No pages returned for chapter ${chapter.externalId}"))
                }
                Result.Success(DownloadResult(pageUrls = pages, totalPages = pages.size))
            }
        }
    }

    private fun ApiSeriesItem.toSearchResult() = SeriesSearchResult(
        externalId = id,
        title = title,
        coverUrl = coverUrl,
        description = description,
    )

    private fun ApiSeriesDetail.toMetadata() = SeriesMetadata(
        externalId = id,
        title = title,
        coverUrl = coverUrl,
        description = description,
    )

    private fun ApiChapterItem.toMetadata() = ChapterMetadata(
        externalId = id,
        title = title,
        chapterIndex = chapterNumber,
        pageCount = pageCount,
    )
}
