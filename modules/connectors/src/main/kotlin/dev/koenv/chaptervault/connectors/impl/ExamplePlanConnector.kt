package dev.koenv.chaptervault.connectors.impl

import dev.koenv.chaptervault.core.config.ConnectorSpecificConfig
import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.execution.*
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.ratelimit.siteRateLimits
import dev.koenv.chaptervault.core.storage.StorageSink
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Example connector using the new declarative execution plan architecture.
 *
 * This connector demonstrates:
 * 1. `extractData` - Declarative data extraction instead of fetchHtml + Jsoup
 * 2. `bulkDownload` - Concurrent downloads with retry logic
 * 3. Clean separation between WHAT to extract and HOW it's extracted
 *
 * Compare this to the old imperative approach:
 * - Before: fetchHtml + Jsoup.parse + manual element iteration
 * - After: extractData with declarative spec, structured data returned
 *
 * NOTE: Fictional example for "plan-manga.example.com"
 */
class ExamplePlanConnector(
    override val executor: Executor,
    private val connectorConfig: ConnectorSpecificConfig? = null
) : Connector {

    private val logger = LoggerFactory.getLogger(ExamplePlanConnector::class.java)

    override val config = ConnectorConfig(
        id = "example-plan",
        name = "Example Plan Connector",
        version = "2.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = connectorConfig?.rateLimit?.minDelayMillis?.milliseconds ?: 500.milliseconds,
            maxConcurrent = connectorConfig?.rateLimit?.maxConcurrent ?: 2,
            maxRequestsPerWindow = connectorConfig?.rateLimit?.maxRequestsPerWindow ?: 60,
            windowDuration = 60.seconds
        ),
        siteRateLimits = siteRateLimits {
            // Default limits apply to auto-created per-host buckets
            defaults {
                maxConcurrent = 2
                minDelay = 500.milliseconds
                maxRequestsPerWindow = 60
            }
            // CDN images are served from a separate domain, no rate limiting needed
            bucket("cdn") { unlimited() }
            // API endpoints can handle higher throughput than HTML pages
            bucket("api") {
                maxConcurrent = 4
                minDelay = 100.milliseconds
                maxRequestsPerWindow = 120
            }
        },
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = false,
            supportsBatchDownload = true,
            supportsPageCount = true,
            maxConcurrentDownloads = 3
        ),
        priority = connectorConfig?.priority ?: 10
    )

    override val baseUrls = listOf(
        "plan-manga.example.com",
        "www.plan-manga.example.com"
    )

    // ========================================================================
    // Search - Demonstrates extractData
    // ========================================================================

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        logger.info("Searching for: {}", query)

        // Declarative extraction: specify WHAT we want, not HOW to parse it
        val plan = executionPlan {
            extractData(
                url = "https://plan-manga.example.com/search?q=${encodeUrl(query)}",
                headers = defaultHeaders(),
                id = "search"
            ) {
                nestedList("results", ".search-result, .manga-card") {
                    href("url", "a[href]")
                    text("title", ".title, h3")
                    text("description", ".description")
                    src("coverUrl", "img")
                }
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val extracted = results["search"] as? ExtractedDataResult

        if (extracted?.success != true) {
            logger.warn("Search failed: {}", extracted?.error)
            return emptyList()
        }

        // Work with structured data instead of parsing HTML
        return extracted.getObjectList("results")?.mapNotNull { item ->
            val url = item["url"] as? String
            if (url.isNullOrBlank()) return@mapNotNull null

            SeriesSearchResult(
                url = url,
                title = item["title"] as? String ?: "Unknown",
                externalId = url.substringAfterLast("/"),
                description = item["description"] as? String,
                coverUrl = item["coverUrl"] as? String
            )
        }?.also {
            logger.info("Found {} results", it.size)
        } ?: emptyList()
    }

    // ========================================================================
    // Series Metadata - Demonstrates extractData for single entity
    // ========================================================================

    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        logger.info("Fetching series: {}", seriesUrl)

        val plan = executionPlan {
            extractData(
                url = seriesUrl,
                headers = defaultHeaders(),
                id = "series"
            ) {
                text("title", "h1, .series-title")
                text("author", ".author")
                text("description", ".description, .synopsis")
                src("coverUrl", ".cover img")
                textList("tags", ".tags a, .genre a")
                text("status", ".status")
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val extracted = (results["series"] as? ExtractedDataResult)?.requireSuccess()
            ?: throw ExecutionException("Failed to fetch series", ActionResult.failure("series", "No result"))

        return SeriesMetadata(
            url = seriesUrl,
            title = extracted.getString("title") ?: "Unknown",
            externalId = seriesUrl.substringAfterLast("/"),
            description = extracted.getString("description"),
            author = extracted.getString("author"),
            coverUrl = extracted.getString("coverUrl"),
            tags = extracted.getStringList("tags") ?: emptyList(),
            status = parseStatus(extracted.getString("status")?.lowercase() ?: "")
        )
    }

    // ========================================================================
    // Chapter List - Demonstrates extractData for lists
    // ========================================================================

    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        logger.info("Fetching chapters: {}", seriesUrl)

        val plan = executionPlan {
            extractData(
                url = seriesUrl,
                headers = defaultHeaders(),
                id = "chapters"
            ) {
                nestedList("chapters", ".chapter-list li, .chapter-item") {
                    href("url", "a[href]")
                    text("fullTitle", "a")
                    text("date", ".date, time", attribute = "datetime")
                    text("dateText", ".date, time")
                }
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val extracted = (results["chapters"] as? ExtractedDataResult)?.requireSuccess()
            ?: throw ExecutionException("Failed to fetch chapters", ActionResult.failure("chapters", "No result"))

        return extracted.getObjectList("chapters")?.mapNotNull { item ->
            val chapterUrl = item["url"] as? String
            if (chapterUrl.isNullOrBlank()) return@mapNotNull null

            val fullTitle = item["fullTitle"] as? String ?: ""
            val (number, title) = parseChapterTitle(fullTitle)
            val dateText = (item["date"] as? String)?.ifBlank { item["dateText"] as? String }

            ChapterMetadata(
                url = chapterUrl,
                seriesUrl = seriesUrl,
                title = title,
                chapterNumber = number,
                externalId = chapterUrl.substringAfterLast("/"),
                chapterIndex = number.toFloatOrNull()?.times(1000)?.toInt(),
                publishDate = dateText,
                pageCount = null
            )
        }?.sortedBy { it.chapterNumber.toDoubleOrNull() ?: 0.0 }?.also {
            logger.info("Found {} chapters", it.size)
        } ?: emptyList()
    }

    // ========================================================================
    // Download Chapter - Demonstrates bulkDownload
    // ========================================================================

    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        logger.info("Downloading chapter: {}", chapterUrl)

        // Step 1: Extract page URLs from the reader page
        val readerPlan = executionPlan {
            extractData(
                url = chapterUrl,
                headers = defaultHeaders(),
                referer = extractSeriesUrl(chapterUrl),
                id = "reader"
            ) {
                // Try multiple selectors for different page structures
                textList("pageUrls", "img.page, img.reader-image, .page-container img", attribute = "src")
                textList("dataSrcUrls", "[data-src], [data-page-url]", attribute = "data-src")
                // Also capture any JS-embedded page data
                text("pageScript", "script:containsData(pages)")
            }
        }

        val readerResults = executor.executeAll(readerPlan.instructions, getExecutionContext())
        val readerData = (readerResults["reader"] as? ExtractedDataResult)?.requireSuccess()
            ?: throw ExecutionException("Failed to fetch reader", ActionResult.failure("reader", "No result"))

        // Determine page URLs from extracted data
        var pageUrls = readerData.getStringList("pageUrls")?.filter { it.isNotBlank() } ?: emptyList()
        if (pageUrls.isEmpty()) {
            pageUrls = readerData.getStringList("dataSrcUrls")?.filter { it.isNotBlank() } ?: emptyList()
        }
        if (pageUrls.isEmpty()) {
            // Try parsing from script content
            val scriptContent = readerData.getString("pageScript") ?: ""
            pageUrls = extractPageUrlsFromScript(scriptContent)
        }

        if (pageUrls.isEmpty()) {
            throw IllegalStateException("No pages found for chapter: $chapterUrl")
        }

        logger.info("Found {} pages to download", pageUrls.size)

        // Step 2: Download all pages using bulkDownload
        // Tag items with "cdn" bucket since page images are served from a CDN domain
        // that can handle much higher throughput (configured as unlimited above)
        val downloadPlan = executionPlan {
            bulkDownload(retries = 2, id = "pages") {
                pageUrls.forEachIndexed { index, url ->
                    item("page-$index", url, headers = defaultHeaders(), referer = chapterUrl, rateLimitBucket = "cdn")
                }
            }
        }

        val downloadResults = executor.executeAll(downloadPlan.instructions, getExecutionContext())
        val bulkResult = downloadResults["pages"] as? BulkDownloadResult
            ?: throw ExecutionException("Failed to download pages", ActionResult.failure("pages", "No result"))

        // Step 3: Write successful downloads to storage
        bulkResult.forEachSuccess { id, bytes, mimeType ->
            val index = id.removePrefix("page-").toInt()
            storage.writePage(index, bytes, mimeType ?: "image/jpeg")
            logger.debug("Downloaded page {}/{}", index + 1, pageUrls.size)
        }

        // Log any failures
        bulkResult.failedItems().forEach { (id, result) ->
            val index = id.removePrefix("page-").toIntOrNull() ?: -1
            logger.warn("Failed to download page {}: {}", index + 1, result.error)
        }

        logger.info("Chapter download complete: {}/{} pages", bulkResult.successCount, pageUrls.size)
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private fun extractPageUrlsFromScript(scriptContent: String): List<String> {
        val jsPattern = Regex("""(?:pages|images)\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val jsMatch = jsPattern.find(scriptContent) ?: return emptyList()

        val urlPattern = Regex(""""(https?://[^"]+)"""")
        return urlPattern.findAll(jsMatch.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    private fun parseChapterTitle(fullTitle: String): Pair<String, String> {
        val patterns = listOf(
            Regex("""(?:Chapter|Ch\.?)\s*(\d+(?:\.\d+)?)\s*[:\-]?\s*(.*)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+(?:\.\d+)?)\s*[:\-]\s*(.+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(fullTitle)
            if (match != null) {
                val number = match.groupValues[1]
                val title = match.groupValues[2].trim().ifBlank { "Chapter $number" }
                return number to title
            }
        }

        val numberMatch = Regex("""(\d+(?:\.\d+)?)""").find(fullTitle)
        return (numberMatch?.groupValues?.get(1) ?: "0") to fullTitle.trim()
    }

    private fun parseStatus(statusText: String): SeriesStatus {
        return when {
            "ongoing" in statusText -> SeriesStatus.ONGOING
            "completed" in statusText -> SeriesStatus.COMPLETED
            "hiatus" in statusText -> SeriesStatus.HIATUS
            "cancelled" in statusText -> SeriesStatus.CANCELLED
            else -> SeriesStatus.UNKNOWN
        }
    }

    private fun encodeUrl(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }
}
