package dev.koenv.chaptervault.extensions.connectors.sources.mangadex

import dev.koenv.chaptervault.extensions.connectors.Bucket
import dev.koenv.chaptervault.extensions.connectors.BucketConfig
import dev.koenv.chaptervault.extensions.connectors.BucketKey
import dev.koenv.chaptervault.extensions.connectors.ChapterMetadata
import dev.koenv.chaptervault.extensions.connectors.DownloadPage
import dev.koenv.chaptervault.extensions.connectors.DownloadResult
import dev.koenv.chaptervault.extensions.connectors.HttpConnector
import dev.koenv.chaptervault.extensions.connectors.SeriesMetadata
import dev.koenv.chaptervault.extensions.connectors.SeriesSearchResult
import dev.koenv.chaptervault.extensions.connectors.getJson
import dev.koenv.chaptervault.extensions.connectors.lenientJson
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexAtHomeResponse
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexChapterData
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexChapterListResponse
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexCoverArtAttributes
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexMangaAttributes
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexMangaData
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexMangaResponse
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexRelationship
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto.MangaDexSearchResponse
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.ConcurrentHashMap

class MangaDexConnector(
    httpClient: HttpClient,
) : HttpConnector(httpClient) {
    override val id = "mangadex"
    override val name = "MangaDex"

    override val bucketConfigs: Map<BucketKey, BucketConfig> =
        mapOf(
            Bucket.API to BucketConfig(requestsPerSecond = 3.0, burst = 3),
            Bucket.CDN to BucketConfig(requestsPerSecond = 3.0, burst = 5),
        )

    override fun supportedLanguages(): List<String> =
        listOf(
            "en",
            "fr",
            "es",
            "es-la",
            "pt",
            "pt-br",
            "de",
            "it",
            "ru",
            "pl",
            "tr",
            "nl",
            "ar",
            "zh",
            "zh-hk",
            "ja",
            "ja-ro",
            "ko",
            "ko-ro",
            "id",
            "vi",
            "th",
            "uk",
            "hu",
            "cs",
            "ro",
            "bg",
            "hr",
            "sr",
        )

    private data class AtHomeCache(
        val baseUrl: String,
        val hash: String,
        val filenames: List<String>,
        val timestamp: Long,
    )

    private val tokenCache = ConcurrentHashMap<String, AtHomeCache>()

    // one mutex per chapter ID so concurrent downloads of different chapters don't block each other
    private val tokenCacheLocks = ConcurrentHashMap<String, Mutex>()

    companion object {
        private const val API_URL = "https://api.mangadex.org"
        private const val CDN_URL = "https://uploads.mangadex.org"
        private const val TOKEN_LIFETIME_MS = 5 * 60 * 1000L
    }

    override suspend fun search(
        query: String,
        request: PageRequest,
    ): Result<Pagination<SeriesSearchResult>> {
        val url = "$API_URL/manga?includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
        val params =
            buildMap<String, String> {
                put("limit", request.size.toString())
                put("offset", (request.page * request.size).toString())
                if (query.isNotBlank()) put("title", query)
            }
        return when (val r = context.getJson<MangaDexSearchResponse>(url, params = params)) {
            is Result.Failure -> r
            is Result.Success ->
                Result.Success(
                    Pagination(
                        items = r.value.data.map { it.toSearchResult() },
                        page = request.page,
                        size = request.size,
                        totalItems = r.value.total.toLong(),
                    ),
                )
        }
    }

    override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> {
        val url = "$API_URL/manga/$externalId?includes[]=cover_art&includes[]=author&includes[]=artist"
        return when (val r = context.getJson<MangaDexMangaResponse>(url)) {
            is Result.Failure -> r
            is Result.Success -> {
                val data =
                    r.value.data
                        ?: return Result.Failure(AppError.NotFound("manga", externalId))
                Result.Success(data.toMetadata())
            }
        }
    }

    override suspend fun fetchChapters(
        externalId: String,
        language: String,
    ): Result<List<ChapterMetadata>> {
        val allChapters = mutableListOf<MangaDexChapterData>()
        val lang = language.ifBlank { "en" }
        var offset = 0

        while (true) {
            val url =
                buildString {
                    append("$API_URL/manga/$externalId/feed")
                    append("?translatedLanguage[]=$lang")
                    append("&order[chapter]=asc")
                    append("&includes[]=scanlation_group")
                    append("&limit=500")
                    append("&offset=$offset")
                }
            when (val r = context.getJson<MangaDexChapterListResponse>(url)) {
                is Result.Failure -> return r
                is Result.Success -> {
                    val page = r.value
                    allChapters.addAll(page.data)
                    if (offset + page.data.size >= page.total) break
                    offset += page.data.size
                }
            }
        }

        return Result.Success(
            allChapters
                .filter { it.attributes.externalUrl == null && it.attributes.pages > 0 }
                .map { it.toChapterMetadata() },
        )
    }

    override suspend fun download(
        chapter: Chapter,
        format: ChapterFormat,
    ): Result<DownloadResult> {
        return when (val r = resolveAtHome(chapter.externalId)) {
            is Result.Failure -> r
            is Result.Success -> {
                val cached = r.value
                if (cached.filenames.isEmpty()) {
                    return Result.Failure(AppError.InternalError("No pages returned for chapter ${chapter.externalId}"))
                }
                Result.Success(
                    DownloadResult(
                        pages =
                            cached.filenames.mapIndexed { i, filename ->
                                DownloadPage(
                                    url = "${cached.baseUrl}/data/${cached.hash}/$filename",
                                    index = i,
                                )
                            },
                    ),
                )
            }
        }
    }

    private suspend fun resolveAtHome(chapterId: String): Result<AtHomeCache> {
        val lock = tokenCacheLocks.computeIfAbsent(chapterId) { Mutex() }
        return lock.withLock {
            val cached = tokenCache[chapterId]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < TOKEN_LIFETIME_MS) {
                return@withLock Result.Success(cached)
            }
            when (val r = context.getJson<MangaDexAtHomeResponse>("$API_URL/at-home/server/$chapterId")) {
                is Result.Failure -> r
                is Result.Success -> {
                    val entry =
                        AtHomeCache(
                            baseUrl = r.value.baseUrl,
                            hash = r.value.chapter.hash,
                            filenames = r.value.chapter.data,
                            timestamp = System.currentTimeMillis(),
                        )
                    tokenCache[chapterId] = entry
                    Result.Success(entry)
                }
            }
        }
    }

    private fun MangaDexMangaData.toSearchResult() =
        SeriesSearchResult(
            externalId = id,
            title = attributes.preferredTitle(),
            coverUrl = relationships.coverUrl(id),
            description = attributes.preferredDescription(),
        )

    private fun MangaDexMangaData.toMetadata() =
        SeriesMetadata(
            externalId = id,
            title = attributes.preferredTitle(),
            coverUrl = relationships.coverUrl(id),
            description = attributes.preferredDescription(),
        )

    private fun MangaDexChapterData.toChapterMetadata() =
        ChapterMetadata(
            externalId = id,
            title =
                attributes.title?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${attributes.chapter ?: id}",
            chapterIndex = attributes.chapter?.toDoubleOrNull() ?: 0.0,
            pageCount = attributes.pages,
        )

    private fun MangaDexMangaAttributes.preferredTitle(): String = title["en"] ?: title.values.firstOrNull() ?: ""

    private fun MangaDexMangaAttributes.preferredDescription(): String? =
        description["en"]?.takeIf { it.isNotBlank() }
            ?: description.values.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun List<MangaDexRelationship>.coverUrl(mangaId: String): String? {
        val fileName =
            firstOrNull { it.type == "cover_art" }
                ?.attributes
                ?.let { runCatching { lenientJson.decodeFromJsonElement<MangaDexCoverArtAttributes>(it) }.getOrNull() }
                ?.fileName
                ?.takeIf { it.isNotBlank() }
                ?: return null
        return "$CDN_URL/covers/$mangaId/$fileName.512.jpg"
    }
}
