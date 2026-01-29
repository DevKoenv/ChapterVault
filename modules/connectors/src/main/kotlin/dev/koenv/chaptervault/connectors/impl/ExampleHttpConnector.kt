package dev.koenv.chaptervault.connectors.impl

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.storage.StorageSink
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Example HTTP-based connector demonstrating web scraping with ktor-client and jsoup.
 *
 * This connector shows how to:
 * - Make HTTP requests with proper headers
 * - Parse HTML responses with jsoup
 * - Extract metadata from web pages
 * - Download images from URLs
 * - Handle rate limiting and retries
 *
 * NOTE: This is a fictional example for "example-manga.com" - it demonstrates
 * the patterns you would use for a real website.
 */
class ExampleHttpConnector : Connector {

    private val logger = LoggerFactory.getLogger(ExampleHttpConnector::class.java)

    override val config = ConnectorConfig(
        name = "ExampleHttpConnector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 500.milliseconds,      // 500ms between requests
            maxConcurrent = 2,                 // Max 2 concurrent requests
            maxRequestsPerWindow = 30,         // Max 30 requests per minute
            windowDuration = 60.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = false,
            supportsBatchDownload = true,
            supportsPageCount = true,
            maxConcurrentDownloads = 2
        ),
        priority = 10  // Higher priority than mock connectors
    )

    override val baseUrls = listOf(
        "https://example-manga.com/*",
        "https://www.example-manga.com/*"
    )

    /**
     * HTTP client with proper configuration for web scraping.
     */
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000  // 30 second timeout
        }
        // Don't follow redirects automatically - handle them manually if needed
        followRedirects = true
    }

    /**
     * Common headers to appear as a normal browser.
     */
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate",
        "Connection" to "keep-alive"
    )

    /**
     * Search for series by query.
     *
     * Demonstrates:
     * - Building search URLs with query parameters
     * - Parsing search result pages
     * - Extracting series info from HTML elements
     */
    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        logger.info("Searching for: {}", query)

        val searchUrl = "https://example-manga.com/search?q=${query.encodeURLParameter()}"
        val html = fetchHtml(searchUrl)
        val doc = parseHtml(html, searchUrl)

        // Example: Parse search results from a typical manga site structure
        // <div class="search-result">
        //   <a href="/manga/some-series" class="title">Series Title</a>
        //   <img src="/covers/123.jpg" class="cover">
        //   <p class="description">Series description...</p>
        // </div>
        val results = doc.select(".search-result").map { element ->
            val link = element.selectFirst("a.title")
            val url = link?.attr("abs:href") ?: ""
            val title = link?.text() ?: "Unknown"
            val coverUrl = element.selectFirst("img.cover")?.attr("abs:src")
            val description = element.selectFirst(".description")?.text()

            SeriesSearchResult(
                url = url,
                title = title,
                description = description,
                coverUrl = coverUrl
            )
        }.filter { it.url.isNotBlank() }

        logger.info("Found {} results for query '{}'", results.size, query)
        return results
    }

    /**
     * Fetch full series metadata.
     *
     * Demonstrates:
     * - Parsing a series detail page
     * - Extracting structured metadata (author, tags, status)
     * - Handling optional fields
     */
    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        logger.info("Fetching series metadata: {}", seriesUrl)

        val html = fetchHtml(seriesUrl)
        val doc = parseHtml(html, seriesUrl)

        // Example: Parse series page structure
        // <h1 class="series-title">Series Title</h1>
        // <div class="author">By: Author Name</div>
        // <div class="status">Status: Ongoing</div>
        // <img class="cover" src="/covers/large/123.jpg">
        // <div class="description">Full description...</div>
        // <div class="tags"><span class="tag">Action</span>...</div>
        val title = doc.selectFirst("h1.series-title")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: "Unknown Series"

        val author = doc.selectFirst(".author")?.text()
            ?.removePrefix("By:")?.trim()

        val coverUrl = doc.selectFirst("img.cover")?.attr("abs:src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".description")?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select(".tags .tag").map { it.text() }

        val statusText = doc.selectFirst(".status")?.text()?.lowercase() ?: ""
        val status = when {
            "ongoing" in statusText -> SeriesStatus.ONGOING
            "completed" in statusText || "finished" in statusText -> SeriesStatus.COMPLETED
            "hiatus" in statusText -> SeriesStatus.HIATUS
            "cancelled" in statusText || "dropped" in statusText -> SeriesStatus.CANCELLED
            else -> SeriesStatus.UNKNOWN
        }

        logger.info("Parsed series: {} by {} ({})", title, author, status)

        return SeriesMetadata(
            url = seriesUrl,
            title = title,
            description = description,
            author = author,
            coverUrl = coverUrl,
            tags = tags,
            status = status
        )
    }

    /**
     * Fetch chapter list for a series.
     *
     * Demonstrates:
     * - Parsing chapter listing pages
     * - Extracting chapter numbers from various formats
     * - Handling pagination (if needed)
     */
    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        logger.info("Fetching chapter list: {}", seriesUrl)

        val html = fetchHtml(seriesUrl)
        val doc = parseHtml(html, seriesUrl)

        // Example: Parse chapter list
        // <ul class="chapter-list">
        //   <li>
        //     <a href="/manga/series/chapter-1">Chapter 1: Title</a>
        //     <span class="date">2024-01-15</span>
        //   </li>
        // </ul>
        val chapters = doc.select(".chapter-list li, .chapter-list .chapter-item").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val chapterUrl = link.attr("abs:href")
            if (chapterUrl.isBlank()) return@mapNotNull null

            val fullTitle = link.text()
            val (chapterNumber, title) = parseChapterTitle(fullTitle)

            val dateText = element.selectFirst(".date, .chapter-date, time")?.let { dateEl ->
                dateEl.attr("datetime").ifBlank { dateEl.text() }
            }

            ChapterMetadata(
                url = chapterUrl,
                seriesUrl = seriesUrl,
                title = title,
                chapterNumber = chapterNumber,
                publishDate = dateText,
                pageCount = null  // Will be determined during download
            )
        }

        // Sort by chapter number (ascending)
        val sortedChapters = chapters.sortedBy { parseChapterNumber(it.chapterNumber) }
        logger.info("Found {} chapters", sortedChapters.size)

        return sortedChapters
    }

    /**
     * Download a chapter's pages.
     *
     * Demonstrates:
     * - Fetching the chapter reader page
     * - Extracting image URLs from various page structures
     * - Downloading images with proper headers (referer, etc.)
     * - Writing pages to storage
     */
    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        logger.info("Downloading chapter: {}", chapterUrl)

        val html = fetchHtml(chapterUrl)
        val doc = parseHtml(html, chapterUrl)

        // Extract page URLs - sites use various methods:
        // 1. Direct img tags: <img class="page" src="...">
        // 2. JavaScript arrays: var pages = ["url1", "url2", ...]
        // 3. Data attributes: <div data-src="...">
        // 4. JSON in script tags
        val pageUrls = extractPageUrls(doc, html, chapterUrl)

        if (pageUrls.isEmpty()) {
            throw IllegalStateException("No pages found for chapter: $chapterUrl")
        }

        logger.info("Found {} pages to download", pageUrls.size)

        pageUrls.forEachIndexed { index, pageUrl ->
            logger.debug("Downloading page {}/{}: {}", index + 1, pageUrls.size, pageUrl)

            val imageBytes = downloadImage(pageUrl, referer = chapterUrl)
            val mimeType = guessMimeType(pageUrl)

            storage.writePage(index, imageBytes, mimeType)
        }

        logger.info("Chapter download complete: {} ({} pages)", chapterUrl, pageUrls.size)
    }

    /**
     * Custom URL extraction for series from chapter URL.
     * Override if the default path-based extraction doesn't work.
     */
    override fun extractSeriesUrl(chapterUrl: String): String {
        // Example: https://example-manga.com/manga/series-name/chapter-1
        // Should return: https://example-manga.com/manga/series-name
        val url = Url(chapterUrl)
        val pathSegments = url.segments.filter { it.isNotBlank() }

        // Remove the last segment (chapter)
        val seriesPath = pathSegments.dropLast(1).joinToString("/")
        return "${url.protocol.name}://${url.host}/$seriesPath"
    }

    // ==================== Private Helper Methods ====================

    /**
     * Fetch HTML content from a URL.
     */
    private suspend fun fetchHtml(url: String): String {
        val response = httpClient.get(url) {
            defaultHeaders.forEach { (key, value) ->
                header(key, value)
            }
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} for $url")
        }

        return response.bodyAsText()
    }

    /**
     * Parse HTML with jsoup, setting the base URI for relative URL resolution.
     */
    private suspend fun parseHtml(html: String, baseUrl: String): Document {
        return withContext(Dispatchers.Default) {
            Jsoup.parse(html, baseUrl)
        }
    }

    /**
     * Download an image from a URL.
     */
    private suspend fun downloadImage(imageUrl: String, referer: String): ByteArray {
        val response = httpClient.get(imageUrl) {
            defaultHeaders.forEach { (key, value) ->
                header(key, value)
            }
            // Important: Many sites check the referer header
            header("Referer", referer)
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} downloading image: $imageUrl")
        }

        return response.readRawBytes()
    }

    /**
     * Extract page image URLs from a chapter page.
     * Tries multiple extraction methods.
     */
    private fun extractPageUrls(doc: Document, html: String, chapterUrl: String): List<String> {
        // Method 1: Direct img tags with class
        var pages = doc.select("img.page-image, img.reader-image, .page img").mapNotNull {
            it.attr("abs:src").ifBlank { null }
        }
        if (pages.isNotEmpty()) return pages

        // Method 2: Data attributes
        pages = doc.select("[data-src], [data-page-url]").mapNotNull {
            val src = it.attr("data-src").ifBlank { it.attr("data-page-url") }
            if (src.isNotBlank()) resolveUrl(src, chapterUrl) else null
        }
        if (pages.isNotEmpty()) return pages

        // Method 3: JavaScript array (common pattern)
        // Look for: var pages = ["url1", "url2"] or similar
        val jsArrayPattern = Regex("""(?:pages|images|chapter_images)\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val jsMatch = jsArrayPattern.find(html)
        if (jsMatch != null) {
            val arrayContent = jsMatch.groupValues[1]
            val urlPattern = Regex(""""(https?://[^"]+)"""")
            pages = urlPattern.findAll(arrayContent).map { it.groupValues[1] }.toList()
            if (pages.isNotEmpty()) return pages
        }

        // Method 4: JSON in script tag
        val jsonPattern = Regex(""""url"\s*:\s*"(https?://[^"]+\.(?:jpg|jpeg|png|webp|gif))"""", RegexOption.IGNORE_CASE)
        pages = jsonPattern.findAll(html).map { it.groupValues[1] }.toList()
        if (pages.isNotEmpty()) return pages

        return emptyList()
    }

    /**
     * Parse chapter title to extract number and name.
     * Handles formats like:
     * - "Chapter 1: The Beginning"
     * - "Ch. 1.5 - Side Story"
     * - "Episode 10"
     */
    private fun parseChapterTitle(fullTitle: String): Pair<String, String> {
        val patterns = listOf(
            Regex("""(?:Chapter|Ch\.?|Episode|Ep\.?)\s*(\d+(?:\.\d+)?)\s*[:\-]?\s*(.*)""", RegexOption.IGNORE_CASE),
            Regex("""#(\d+(?:\.\d+)?)\s*[:\-]?\s*(.*)"""),
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

        // Fallback: try to find any number
        val numberMatch = Regex("""(\d+(?:\.\d+)?)""").find(fullTitle)
        val number = numberMatch?.groupValues?.get(1) ?: "0"
        return number to fullTitle.trim()
    }

    /**
     * Parse chapter number string to double for sorting.
     */
    private fun parseChapterNumber(chapterNumber: String): Double {
        return chapterNumber.toDoubleOrNull() ?: 0.0
    }

    /**
     * Resolve a potentially relative URL to absolute.
     */
    private fun resolveUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = Url(baseUrl)
                "${base.protocol.name}://${base.host}$url"
            }
            else -> {
                val base = Url(baseUrl)
                val basePath = base.encodedPath.substringBeforeLast('/')
                "${base.protocol.name}://${base.host}$basePath/$url"
            }
        }
    }

    /**
     * Guess MIME type from URL/extension.
     */
    private fun guessMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".png") -> "image/png"
            lower.contains(".gif") -> "image/gif"
            lower.contains(".webp") -> "image/webp"
            lower.contains(".bmp") -> "image/bmp"
            else -> "image/jpeg"  // Default to JPEG
        }
    }
}
