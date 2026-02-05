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
import dev.koenv.chaptervault.core.storage.StorageSink
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Example connector using browser plans for JS-heavy sites.
 *
 * This demonstrates how to:
 * 1. Use browser instructions for JS-heavy sites that require interaction
 * 2. Chain plans based on intermediate results
 * 3. Mix browser operations with `bulkDownload` for efficient page downloads
 * 4. Extract data from browser elements, then use it for HTTP fetches
 *
 * Key difference from ExamplePlanConnector:
 * - Uses browser for navigation and interaction (search, scroll, etc.)
 * - Uses `bulkDownload` for efficient concurrent image downloads
 * - Preserves browser context for authenticated sites
 *
 * NOTE: Fictional example for "js-plan-manga.example.com"
 */
class ExampleBrowserPlanConnector(
    override val executor: Executor,
    private val connectorConfig: ConnectorSpecificConfig? = null
) : Connector {

    private val logger = LoggerFactory.getLogger(ExampleBrowserPlanConnector::class.java)

    override val config = ConnectorConfig(
        id = "example-browser-plan",
        name = "Example Browser Plan Connector",
        version = "2.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = connectorConfig?.rateLimit?.minDelayMillis?.milliseconds ?: 2.seconds,
            maxConcurrent = connectorConfig?.rateLimit?.maxConcurrent ?: 1,
            maxRequestsPerWindow = connectorConfig?.rateLimit?.maxRequestsPerMinute ?: 20,
            windowDuration = 60.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = false,
            supportsBatchDownload = true,
            supportsPageCount = true,
            maxConcurrentDownloads = 3
        ),
        priority = connectorConfig?.priority ?: 5
    )

    override val baseUrls = listOf(
        "js-plan-manga.example.com",
        "dynamic-plan.example.com"
    )

    override fun getExecutionContext(): ExecutionContext {
        return ExecutionContext(
            connectorName = config.name,
            sessionId = "connector:${config.name}",
            useBrowser = true
        )
    }

    // ========================================================================
    // Search - Browser interaction for JS-heavy search
    // ========================================================================

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        logger.info("Searching for: {}", query)

        // Browser plan for interactive search
        val searchPlan = executionPlan {
            browser {
                navigate("https://js-plan-manga.example.com/search", id = "nav")
                waitForSelector("input[name='q'], input.search-input", id = "wait-input")
                fill("input[name='q'], input.search-input", query, id = "fill")
                click("button[type='submit'], .search-btn", id = "click")
                waitForSelector(".search-result, .manga-card", timeout = 15_000, id = "wait-results")
                queryAll(".search-result, .manga-card", id = "results")
            }
        }

        val results = executor.executeAll(searchPlan.instructions, getExecutionContext())
        val elementsResult = results["results"] as? ElementsResult

        if (elementsResult?.success != true) {
            logger.warn("Search failed: {}", elementsResult?.error)
            return emptyList()
        }

        return elementsResult.elements.mapNotNull { element ->
            val url = element.href()
            if (url.isNullOrBlank()) return@mapNotNull null

            SeriesSearchResult(
                url = resolveUrl(url),
                title = element.textContent?.trim() ?: "Unknown",
                description = element.dataAttr("description"),
                coverUrl = element.src()?.let { resolveUrl(it) }
                    ?: element.dataAttr("cover")?.let { resolveUrl(it) }
            )
        }.also {
            logger.info("Found {} results", it.size)
        }
    }

    // ========================================================================
    // Series Metadata - Browser extraction with JS data fallback
    // ========================================================================

    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        logger.info("Fetching series: {}", seriesUrl)

        val browserPlan = executionPlan {
            browser {
                navigate(seriesUrl, id = "nav")
                waitForSelector(".series-info, .manga-info", id = "wait")

                // Query multiple elements
                queryFirst("h1, .series-title", id = "title")
                queryFirst(".author, [itemprop='author']", id = "author")
                queryFirst(".description, .synopsis", id = "description")
                queryFirst(".cover img", id = "cover")
                queryAll(".tags a, .genre a", id = "tags")
                queryFirst(".status", id = "status")

                // Also try to extract any embedded API data
                evaluate("""
                    (() => {
                        if (window.__SERIES_DATA__) return JSON.stringify(window.__SERIES_DATA__);
                        if (window.__NEXT_DATA__?.props?.pageProps?.series) {
                            return JSON.stringify(window.__NEXT_DATA__.props.pageProps.series);
                        }
                        return null;
                    })()
                """.trimIndent(), id = "api-data")
            }
        }

        val results = executor.executeAll(browserPlan.instructions, getExecutionContext())

        // Try to use embedded API data first
        val apiData = (results["api-data"] as? StringResult)?.value
        if (apiData != null && apiData != "null") {
            logger.debug("Using embedded API data")
            // In real implementation, parse the JSON here
        }

        // Fall back to DOM element parsing
        val titleResult = results["title"] as? ElementResult
        val authorResult = results["author"] as? ElementResult
        val descResult = results["description"] as? ElementResult
        val coverResult = results["cover"] as? ElementResult
        val tagsResult = results["tags"] as? ElementsResult
        val statusResult = results["status"] as? ElementResult

        return SeriesMetadata(
            url = seriesUrl,
            title = titleResult?.element?.textContent?.trim() ?: "Unknown",
            description = descResult?.element?.textContent?.trim(),
            author = authorResult?.element?.textContent?.trim(),
            coverUrl = coverResult?.element?.src()?.let { resolveUrl(it) },
            tags = tagsResult?.elements?.mapNotNull { it.textContent?.trim() } ?: emptyList(),
            status = parseStatus(statusResult?.element?.textContent?.lowercase() ?: "")
        )
    }

    // ========================================================================
    // Chapter List - Browser with scroll handling
    // ========================================================================

    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        logger.info("Fetching chapters: {}", seriesUrl)

        val plan = executionPlan {
            browser {
                navigate(seriesUrl, id = "nav")
                waitForSelector(".chapter-list, .chapters", id = "wait")

                // Handle lazy loading
                scrollToBottom(maxScrolls = 10, delayMs = 500, id = "scroll")

                // Query all chapters
                queryAll(".chapter-item, .chapter-row, .chapter-list li", id = "chapters")
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val chaptersResult = results["chapters"] as? ElementsResult

        if (chaptersResult?.success != true) {
            logger.warn("Failed to fetch chapters: {}", chaptersResult?.error)
            return emptyList()
        }

        return chaptersResult.elements.mapNotNull { element ->
            val chapterUrl = element.href()
            if (chapterUrl.isNullOrBlank()) return@mapNotNull null

            val fullTitle = element.textContent?.trim() ?: return@mapNotNull null
            val (number, title) = parseChapterTitle(fullTitle)

            ChapterMetadata(
                url = resolveUrl(chapterUrl),
                seriesUrl = seriesUrl,
                title = title,
                chapterNumber = number,
                publishDate = element.dataAttr("date"),
                pageCount = element.dataAttr("pages")?.toIntOrNull()
            )
        }.sortedBy { it.chapterNumber.toDoubleOrNull() ?: 0.0 }.also {
            logger.info("Found {} chapters", it.size)
        }
    }

    // ========================================================================
    // Download - Browser extraction + bulkDownload
    // ========================================================================

    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        logger.info("Downloading chapter: {}", chapterUrl)

        // Step 1: Use browser to load reader and extract page URLs
        val readerPlan = executionPlan {
            browser {
                navigate(chapterUrl, id = "nav")
                waitForSelector(".reader-image, .page-image, #reader img", timeout = 30_000, id = "wait")

                // Scroll to trigger lazy loading
                scrollToBottom(maxScrolls = 20, delayMs = 300, id = "scroll")
                scroll(0, -99999, id = "scroll-top")

                // Query images from DOM
                queryAll(".reader-image, .page-image, #reader img", id = "images")

                // Also try to get URLs from JavaScript
                evaluate("""
                    (() => {
                        if (typeof pages !== 'undefined' && Array.isArray(pages)) return JSON.stringify(pages);
                        if (typeof chapter_images !== 'undefined') return JSON.stringify(chapter_images);
                        if (window.readerData?.pages) return JSON.stringify(window.readerData.pages);
                        return '[]';
                    })()
                """.trimIndent(), id = "js-pages")
            }
        }

        val readerResults = executor.executeAll(readerPlan.instructions, getExecutionContext())

        // Extract page URLs from DOM or JavaScript
        var pageUrls = extractPageUrlsFromDom(readerResults)
        if (pageUrls.isEmpty()) {
            pageUrls = extractPageUrlsFromJs(readerResults)
        }

        if (pageUrls.isEmpty()) {
            throw IllegalStateException("No pages found for chapter: $chapterUrl")
        }

        logger.info("Found {} pages to download", pageUrls.size)

        // Step 2: Download pages using bulkDownload (more efficient than individual browser downloads)
        val downloadPlan = executionPlan {
            bulkDownload(maxConcurrency = 3, retries = 2, id = "pages") {
                pageUrls.forEachIndexed { index, url ->
                    item("page-$index", url, referer = chapterUrl)
                }
            }
        }

        val downloadResults = executor.executeAll(downloadPlan.instructions, getExecutionContext())
        val bulkResult = downloadResults["pages"] as? BulkDownloadResult
            ?: throw ExecutionException("Failed to download pages", ActionResult.failure("pages", "No result"))

        // Step 3: Write successful downloads to storage
        bulkResult.forEachSuccess { id, bytes, mimeType ->
            val index = id.removePrefix("page-").toInt()
            storage.writePage(index, bytes, mimeType ?: guessMimeType(pageUrls[index]))
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

    private fun extractPageUrlsFromDom(results: Map<String, ExecutionResult>): List<String> {
        val imagesResult = results["images"] as? ElementsResult ?: return emptyList()

        return imagesResult.elements.mapNotNull { element ->
            element.src()
                ?: element.dataAttr("src")
                ?: element.dataAttr("original")
        }.filter { it.isNotBlank() }.map { resolveUrl(it) }
    }

    private fun extractPageUrlsFromJs(results: Map<String, ExecutionResult>): List<String> {
        val jsResult = results["js-pages"] as? StringResult ?: return emptyList()
        val jsValue = jsResult.value ?: return emptyList()

        return try {
            jsValue.removeSurrounding("\"")
                .removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.startsWith("http") || it.startsWith("/") }
                .map { resolveUrl(it) }
        } catch (e: Exception) {
            logger.debug("Failed to parse JS page URLs: {}", e.message)
            emptyList()
        }
    }

    private fun parseChapterTitle(fullTitle: String): Pair<String, String> {
        val patterns = listOf(
            Regex("""(?:Chapter|Ch\.?|Episode|Ep\.?)\s*(\d+(?:\.\d+)?)\s*[:\-]?\s*(.*)""", RegexOption.IGNORE_CASE),
            Regex("""#(\d+(?:\.\d+)?)\s*[:\-]?\s*(.*)""")
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

    private fun resolveUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://js-plan-manga.example.com$url"
            else -> "https://js-plan-manga.example.com/$url"
        }
    }

    private fun guessMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            ".png" in lower -> "image/png"
            ".gif" in lower -> "image/gif"
            ".webp" in lower -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
