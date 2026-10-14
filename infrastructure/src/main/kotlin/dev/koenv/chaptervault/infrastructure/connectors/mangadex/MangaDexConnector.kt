package dev.koenv.chaptervault.infrastructure.connectors.mangadex

import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexAtHomeResponse
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexChapterData
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexChapterListResponse
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexCoverArtAttributes
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexMangaAttributes
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexMangaData
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexMangaResponse
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexRelationship
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto.MangaDexSearchResponse
import dev.koenv.chaptervault.kernel.connector.Bucket
import dev.koenv.chaptervault.kernel.connector.BucketConfig
import dev.koenv.chaptervault.kernel.connector.BucketKey
import dev.koenv.chaptervault.kernel.connector.ChapterMetadata
import dev.koenv.chaptervault.kernel.connector.DownloadPage
import dev.koenv.chaptervault.kernel.connector.DownloadResult
import dev.koenv.chaptervault.kernel.connector.HttpConnector
import dev.koenv.chaptervault.kernel.connector.SeriesMetadata
import dev.koenv.chaptervault.kernel.connector.SeriesSearchResult
import dev.koenv.chaptervault.kernel.connector.getJson
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

private val lenientJson = Json { ignoreUnknownKeys = true }

class MangaDexConnector(
    private val httpClient: HttpClient,
    contentRatings: List<String> = listOf("safe", "suggestive"),
    private val tokenLifetimeMs: Long = TOKEN_LIFETIME_MS,
) : HttpConnector(httpClient) {
    private val ratingParams = contentRatings.joinToString("") { "&contentRating[]=${it.lowercase()}" }

    override val id = "mangadex"
    override val name = "MangaDex"

    override val bucketConfigs: Map<BucketKey, BucketConfig> =
        mapOf(
            Bucket.API to BucketConfig(requestsPerSecond = 3.0, burst = 3),
            Bucket.CDN to BucketConfig(requestsPerSecond = 3.0, burst = 5),
            // MangaDex allows 40 at-home lookups per minute
            AT_HOME to BucketConfig(requestsPerSecond = 40.0 / 60.0, burst = 40),
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

    internal fun cachedTokenCount(): Int = tokenCache.size

    companion object {
        private const val API_URL = "https://api.mangadex.org"
        private const val CDN_URL = "https://uploads.mangadex.org"
        private const val MAX_SEARCH_PAGE_SIZE = 100
        private const val TOKEN_LIFETIME_MS = 5 * 60 * 1000L
        private const val REPORT_URL = "https://api.mangadex.network/report"
        private const val REPORT_TIMEOUT_MS = 5_000L

        private val AT_HOME =
            object : BucketKey {
                override val id = "at-home"
            }
    }

    override suspend fun fetchPage(page: DownloadPage): Result<ByteArray> {
        val started = System.nanoTime()
        val result = super.fetchPage(page)
        val bytes = (result as? Result.Success)?.value?.size ?: 0
        if (result is Result.Failure) {
            // the at-home base URL may have expired or the node may be bad: ask for a new one next time
            tokenCache.entries.removeIf { (_, c) -> page.url.startsWith("${c.baseUrl}/data/${c.hash}/") }
        }
        report(page.url, result is Result.Success, bytes, (System.nanoTime() - started) / 1_000_000)
        return result
    }

    // MangaDex asks clients to report every image served by a MangaDex@Home node; best effort, never fails a download
    private suspend fun report(
        imageUrl: String,
        success: Boolean,
        bytes: Int,
        durationMs: Long,
    ) {
        if (runCatching { URI(imageUrl).host }.getOrNull()?.endsWith("mangadex.org") != false) return
        val body =
            buildJsonObject {
                put("url", imageUrl)
                put("success", success)
                put("cached", false) // the page download does not expose the X-Cache header
                put("bytes", bytes)
                put("duration", durationMs)
            }
        try {
            withTimeoutOrNull(REPORT_TIMEOUT_MS) {
                httpClient.post(REPORT_URL) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // reporting is optional
        }
    }

    override suspend fun search(
        query: String,
        request: PageRequest,
    ): Result<Pagination<SeriesSearchResult>> {
        val url = "$API_URL/manga?includes[]=cover_art$ratingParams"
        // GET /manga allows at most 100 per request (limit maximum in MangaDex's api.yaml); the page size reported back is the real one
        val size = minOf(request.size, MAX_SEARCH_PAGE_SIZE)
        val params =
            buildMap<String, String> {
                put("limit", size.toString())
                put("offset", (request.page * size).toString())
                if (query.isNotBlank()) put("title", query)
            }
        return when (val r = context.getJson<MangaDexSearchResponse>(url, params = params)) {
            is Result.Failure -> r
            is Result.Success ->
                Result.Success(
                    Pagination(
                        items = r.value.data.map { it.toSearchResult() },
                        page = request.page,
                        size = size,
                        totalItems = r.value.total.toLong(),
                    ),
                )
        }
    }

    override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> {
        val url = "$API_URL/manga/${externalId.encodeURLPathPart()}?includes[]=cover_art&includes[]=author&includes[]=artist"
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
                    append("$API_URL/manga/${externalId.encodeURLPathPart()}/feed")
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
                    // an empty page ends the loop even if the total claims more: the offset would never move
                    if (page.data.isEmpty() || offset + page.data.size >= page.total) break
                    offset += page.data.size
                }
            }
        }

        return Result.Success(allChapters.filter { it.attributes.externalUrl == null && it.attributes.pages > 0 }.toChapterMetadata())
    }

    /**
     * Several scanlation groups can release the same chapter number: only the earliest published release is kept.
     * Chapters without a number (oneshots, extras) are indexed after the highest numbered chapter, in publication order.
     */
    private fun List<MangaDexChapterData>.toChapterMetadata(): List<ChapterMetadata> {
        val (numbered, unnumbered) = sortedWith(compareBy({ it.attributes.publishAt }, { it.id })).partition { it.number() != null }
        val firstOfEachNumber = numbered.distinctBy { it.number() }
        val afterLast = (firstOfEachNumber.maxOfOrNull { it.number() ?: 0.0 } ?: 0.0) + 1
        return (
            firstOfEachNumber.map { it.toChapterMetadata(it.number() ?: 0.0) } +
                unnumbered.mapIndexed { i, chapter -> chapter.toChapterMetadata(afterLast + i) }
        ).sortedBy { it.chapterIndex }
    }

    private fun MangaDexChapterData.number(): Double? = attributes.chapter?.toDoubleOrNull()

    override suspend fun download(
        chapter: Chapter,
        format: ChapterFormat,
    ): Result<DownloadResult> {
        val chapterExternalId =
            chapter.externalId
                ?: return Result.Failure(AppError.InternalError("Cannot download chapter ${chapter.id}: no external ID"))
        return when (val r = resolveAtHome(chapterExternalId)) {
            is Result.Failure -> r
            is Result.Success -> {
                val cached = r.value
                if (cached.filenames.isEmpty()) {
                    return Result.Failure(AppError.InternalError("No pages returned for chapter $chapterExternalId"))
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
            if (cached != null && System.currentTimeMillis() - cached.timestamp < tokenLifetimeMs) {
                return@withLock Result.Success(cached)
            }
            when (
                val r =
                    context.getJson<MangaDexAtHomeResponse>(
                        "$API_URL/at-home/server/${chapterId.encodeURLPathPart()}",
                        bucket = AT_HOME,
                    )
            ) {
                is Result.Failure -> r
                is Result.Success -> {
                    val entry =
                        AtHomeCache(
                            baseUrl = r.value.baseUrl,
                            hash = r.value.chapter.hash,
                            filenames = r.value.chapter.data,
                            timestamp = System.currentTimeMillis(),
                        )
                    // expired entries are of no use any more: drop them and their locks so neither map only grows
                    val now = System.currentTimeMillis()
                    val expired = tokenCache.filter { (id, c) -> id != chapterId && now - c.timestamp >= tokenLifetimeMs }.keys
                    expired.forEach {
                        tokenCache.remove(it)
                        tokenCacheLocks.remove(it)
                    }
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

    private fun MangaDexChapterData.toChapterMetadata(index: Double) =
        ChapterMetadata(
            externalId = id,
            title =
                attributes.title?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${attributes.chapter ?: id}",
            chapterIndex = index,
            pageCount = attributes.pages,
        )

    private fun MangaDexMangaAttributes.preferredTitle(): String =
        (title["en"] ?: title.values.firstOrNull())?.takeIf { it.isNotBlank() } ?: "Untitled"

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
