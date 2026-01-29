package dev.koenv.chaptervault.connectors.impl

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Example browser-based connector using Playwright for JavaScript-heavy sites.
 *
 * This connector demonstrates:
 * - Headless browser automation with Playwright
 * - Waiting for JavaScript-rendered content
 * - Handling dynamic page loading (infinite scroll, lazy loading)
 * - Browser resource management and pooling
 * - Cookie/session management
 * - Handling anti-bot protections (Cloudflare, etc.)
 *
 * Use this pattern when:
 * - The site heavily relies on JavaScript to render content
 * - Content is loaded dynamically (AJAX, React, Vue, etc.)
 * - Simple HTTP requests return empty/incomplete pages
 * - The site has anti-bot protections that block regular requests
 *
 * NOTE: This is a fictional example for "js-manga.example.com"
 */
class ExampleBrowserConnector : Connector {

    private val logger = LoggerFactory.getLogger(ExampleBrowserConnector::class.java)

    override val config = ConnectorConfig(
        name = "ExampleBrowserConnector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 2.seconds,              // Browser operations are slower
            maxConcurrent = 1,                 // Only one browser operation at a time
            maxRequestsPerWindow = 20,         // Fewer requests due to overhead
            windowDuration = 60.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = false,
            supportsBatchDownload = true,
            supportsPageCount = true,
            maxConcurrentDownloads = 1         // Sequential for browser stability
        ),
        priority = 5
    )

    override val baseUrls = listOf(
        "https://js-manga.example.com/*",
        "https://dynamic-comics.example.com/*"
    )

    /**
     * HTTP client for downloading images (faster than browser for binary content).
     */
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
        }
    }

    /**
     * Playwright instance - lazily initialized.
     * Managed as a singleton to avoid startup overhead.
     */
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private val browserMutex = Mutex()

    /**
     * Browser context for maintaining session state (cookies, localStorage).
     */
    private var browserContext: BrowserContext? = null

    /**
     * Initialize Playwright browser if not already done.
     * Uses mutex to ensure thread-safe initialization.
     */
    private suspend fun ensureBrowser(): Browser = browserMutex.withLock {
        if (browser == null || !browser!!.isConnected) {
            logger.info("Initializing Playwright browser...")

            withContext(Dispatchers.IO) {
                playwright = Playwright.create()
                browser = playwright!!.chromium().launch(
                    BrowserType.LaunchOptions()
                        .setHeadless(true)  // Run without GUI
                        .setArgs(listOf(
                            "--disable-gpu",
                            "--disable-dev-shm-usage",
                            "--no-sandbox",
                            "--disable-setuid-sandbox"
                        ))
                )

                // Create persistent context with realistic browser fingerprint
                browserContext = browser!!.newContext(
                    Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .setViewportSize(1920, 1080)
                        .setLocale("en-US")
                        .setTimezoneId("America/New_York")
                        // Block unnecessary resources for faster loading
                        .setExtraHTTPHeaders(mapOf(
                            "Accept-Language" to "en-US,en;q=0.9"
                        ))
                )
            }

            logger.info("Playwright browser initialized")
        }
        browser!!
    }

    /**
     * Get or create a page in the browser context.
     */
    private suspend fun withPage(block: suspend (Page) -> Unit) {
        ensureBrowser()
        val page = withContext(Dispatchers.IO) {
            browserContext!!.newPage()
        }
        try {
            block(page)
        } finally {
            withContext(Dispatchers.IO) {
                page.close()
            }
        }
    }

    /**
     * Search for series using browser automation.
     */
    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        logger.info("Browser search for: {}", query)

        val results = mutableListOf<SeriesSearchResult>()

        withPage { page ->
            withContext(Dispatchers.IO) {
                // Navigate to search page
                page.navigate(
                    "https://js-manga.example.com/search",
                    Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE)
                )

                // Type in search box (simulating user input)
                val searchInput = page.locator("input[name='q'], input.search-input, #search-box")
                searchInput.fill(query)

                // Click search button or press enter
                val searchButton = page.locator("button[type='submit'], .search-button")
                if (searchButton.count() > 0) {
                    searchButton.click()
                } else {
                    searchInput.press("Enter")
                }

                // Wait for results to load
                page.waitForLoadState(LoadState.NETWORKIDLE)

                // Wait for specific result elements (with timeout)
                try {
                    page.locator(".search-result, .manga-item").first().waitFor(
                        Locator.WaitForOptions().setTimeout(10000.0)
                    )
                } catch (e: TimeoutError) {
                    logger.warn("No search results found for query: {}", query)
                    return@withContext
                }

                // Extract results from the DOM
                val resultElements = page.locator(".search-result, .manga-item").all()
                for (element in resultElements) {
                    try {
                        val linkEl = element.locator("a").first()
                        val url = linkEl.getAttribute("href") ?: continue
                        val title = linkEl.textContent()?.trim() ?: "Unknown"

                        val coverUrl = element.locator("img").first().getAttribute("src")
                        val description = element.locator(".description, .summary").first()
                            .textContent()?.trim()

                        results.add(
                            SeriesSearchResult(
                                url = resolveUrl(url, "https://js-manga.example.com"),
                                title = title,
                                description = description,
                                coverUrl = coverUrl?.let { resolveUrl(it, "https://js-manga.example.com") }
                            )
                        )
                    } catch (e: Exception) {
                        logger.debug("Failed to parse search result element: {}", e.message)
                    }
                }
            }
        }

        logger.info("Found {} results", results.size)
        return results
    }

    /**
     * Fetch series metadata using browser.
     */
    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        logger.info("Browser fetching series: {}", seriesUrl)

        var metadata: SeriesMetadata? = null

        withPage { page ->
            withContext(Dispatchers.IO) {
                page.navigate(seriesUrl, Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE))

                // Wait for main content to load
                page.waitForSelector(".series-info, .manga-info, article", Page.WaitForSelectorOptions().setTimeout(15000.0))

                // Extract metadata
                val title = page.locator("h1, .series-title, .manga-title").first().textContent()?.trim()
                    ?: "Unknown Series"

                val author = page.locator(".author, [itemprop='author'], .manga-author").first()
                    .textContent()?.trim()?.removePrefix("Author:")?.trim()

                val description = page.locator(".description, .summary, .synopsis, [itemprop='description']").first()
                    .textContent()?.trim()

                val coverUrl = page.locator(".cover img, .manga-cover img, .series-image img").first()
                    .getAttribute("src")

                val tags = page.locator(".tags a, .genres a, .genre-tag").all()
                    .mapNotNull { it.textContent()?.trim() }

                val statusText = page.locator(".status, .series-status").first()
                    .textContent()?.lowercase() ?: ""

                val status = when {
                    "ongoing" in statusText -> SeriesStatus.ONGOING
                    "completed" in statusText -> SeriesStatus.COMPLETED
                    "hiatus" in statusText -> SeriesStatus.HIATUS
                    "cancelled" in statusText -> SeriesStatus.CANCELLED
                    else -> SeriesStatus.UNKNOWN
                }

                metadata = SeriesMetadata(
                    url = seriesUrl,
                    title = title,
                    description = description,
                    author = author,
                    coverUrl = coverUrl?.let { resolveUrl(it, seriesUrl) },
                    tags = tags,
                    status = status
                )
            }
        }

        return metadata ?: throw IllegalStateException("Failed to fetch metadata for: $seriesUrl")
    }

    /**
     * Fetch chapter list - handles infinite scroll and lazy loading.
     */
    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        logger.info("Browser fetching chapters: {}", seriesUrl)

        val chapters = mutableListOf<ChapterMetadata>()

        withPage { page ->
            withContext(Dispatchers.IO) {
                page.navigate(seriesUrl, Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE))

                // Wait for chapter list to appear
                page.waitForSelector(".chapter-list, .chapters, #chapter-list", Page.WaitForSelectorOptions().setTimeout(15000.0))

                // Handle infinite scroll - scroll down until no new chapters appear
                var previousCount = 0
                var attempts = 0
                val maxAttempts = 10

                while (attempts < maxAttempts) {
                    // Scroll to bottom
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                    page.waitForTimeout(1000.0)  // Wait for lazy loading

                    val currentCount = page.locator(".chapter-item, .chapter-row, .chapter-list li").count()
                    if (currentCount == previousCount) {
                        break  // No new chapters loaded
                    }
                    previousCount = currentCount
                    attempts++
                }

                // Click "Load More" button if present
                val loadMoreButton = page.locator("button.load-more, .show-all-chapters, #load-more")
                while (loadMoreButton.count() > 0 && loadMoreButton.isVisible) {
                    try {
                        loadMoreButton.click()
                        page.waitForTimeout(1000.0)
                    } catch (e: Exception) {
                        break
                    }
                }

                // Extract chapter data
                val chapterElements = page.locator(".chapter-item, .chapter-row, .chapter-list li, .chapter-link").all()

                for (element in chapterElements) {
                    try {
                        val link = element.locator("a").first()
                        val chapterUrl = link.getAttribute("href") ?: continue
                        val fullTitle = link.textContent()?.trim() ?: continue

                        // Parse chapter number from title
                        val chapterNumber = extractChapterNumber(fullTitle)

                        // Try to get publish date
                        val dateText = element.locator(".date, .chapter-date, time").first()
                            .let { it.getAttribute("datetime") ?: it.textContent()?.trim() }

                        chapters.add(
                            ChapterMetadata(
                                url = resolveUrl(chapterUrl, seriesUrl),
                                seriesUrl = seriesUrl,
                                title = fullTitle,
                                chapterNumber = chapterNumber,
                                publishDate = dateText,
                                pageCount = null
                            )
                        )
                    } catch (e: Exception) {
                        logger.debug("Failed to parse chapter element: {}", e.message)
                    }
                }
            }
        }

        logger.info("Found {} chapters", chapters.size)
        return chapters.sortedBy { it.chapterNumber.toDoubleOrNull() ?: 0.0 }
    }

    /**
     * Download chapter pages using browser to handle JS-loaded images.
     */
    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        logger.info("Browser downloading chapter: {}", chapterUrl)

        withPage { page ->
            withContext(Dispatchers.IO) {
                // Navigate to reader page
                page.navigate(chapterUrl, Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE))

                // Wait for reader to initialize
                page.waitForSelector(".reader-image, .page-image, #reader img", Page.WaitForSelectorOptions().setTimeout(30000.0))

                // Some readers need time to load all images
                page.waitForTimeout(2000.0)

                // Scroll through to trigger lazy loading of all images
                val totalHeight = page.evaluate("document.body.scrollHeight") as Number
                var currentScroll = 0
                while (currentScroll < totalHeight.toInt()) {
                    page.evaluate("window.scrollTo(0, $currentScroll)")
                    currentScroll += 500
                    page.waitForTimeout(200.0)
                }

                // Scroll back to top
                page.evaluate("window.scrollTo(0, 0)")
                page.waitForTimeout(1000.0)

                // Extract image URLs
                val imageUrls = mutableListOf<String>()

                // Method 1: Direct image elements
                val imgElements = page.locator(".reader-image, .page-image, #reader img, .chapter-image").all()
                for (img in imgElements) {
                    val src = img.getAttribute("src")
                        ?: img.getAttribute("data-src")
                        ?: img.getAttribute("data-original")
                    if (src != null && src.isNotBlank()) {
                        imageUrls.add(resolveUrl(src, chapterUrl))
                    }
                }

                // Method 2: Check for JS-based reader that stores URLs in data
                if (imageUrls.isEmpty()) {
                    val jsUrls = page.evaluate("""
                        () => {
                            // Try common patterns for JS readers
                            if (window.pages) return window.pages;
                            if (window.chapter_images) return window.chapter_images;
                            if (window.readerData?.pages) return window.readerData.pages;

                            // Look for React/Vue state
                            const root = document.querySelector('#root, #app, [data-reactroot]');
                            if (root && root._reactRootContainer) {
                                // React app - would need specific handling
                            }

                            return [];
                        }
                    """) as? List<*>

                    jsUrls?.filterIsInstance<String>()?.forEach { url ->
                        imageUrls.add(resolveUrl(url, chapterUrl))
                    }
                }

                if (imageUrls.isEmpty()) {
                    throw IllegalStateException("No images found in chapter: $chapterUrl")
                }

                logger.info("Found {} pages to download", imageUrls.size)

                // Download images (use HTTP client for efficiency)
                // Get cookies from browser for authenticated requests
                val cookies = browserContext!!.cookies().associate { it.name to it.value }

                imageUrls.forEachIndexed { index, imageUrl ->
                    logger.debug("Downloading page {}/{}", index + 1, imageUrls.size)

                    val imageBytes = downloadImageWithCookies(imageUrl, chapterUrl, cookies)
                    val mimeType = guessMimeType(imageUrl)

                    storage.writePage(index, imageBytes, mimeType)
                }
            }
        }

        logger.info("Chapter download complete")
    }

    /**
     * Clean up browser resources.
     * Should be called when the connector is no longer needed.
     */
    fun close() {
        browserContext?.close()
        browser?.close()
        playwright?.close()
        httpClient.close()
        logger.info("Browser connector resources released")
    }

    // ==================== Helper Methods ====================

    /**
     * Download image with cookies from browser session.
     */
    private suspend fun downloadImageWithCookies(
        imageUrl: String,
        referer: String,
        cookies: Map<String, String>
    ): ByteArray {
        val response = httpClient.get(imageUrl) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            header("Referer", referer)
            header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
            if (cookies.isNotEmpty()) {
                header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
        return response.readRawBytes()
    }

    /**
     * Extract chapter number from title string.
     */
    private fun extractChapterNumber(title: String): String {
        val patterns = listOf(
            Regex("""(?:Chapter|Ch\.?|Episode|Ep\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""#(\d+(?:\.\d+)?)"""),
            Regex("""^(\d+(?:\.\d+)?)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return "0"
    }

    /**
     * Resolve relative URLs.
     */
    private fun resolveUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = baseUrl.substringBefore("/", "https://js-manga.example.com")
                val host = Regex("""https?://[^/]+""").find(baseUrl)?.value ?: base
                "$host$url"
            }
            else -> {
                val basePath = baseUrl.substringBeforeLast('/')
                "$basePath/$url"
            }
        }
    }

    /**
     * Guess MIME type from URL.
     */
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
