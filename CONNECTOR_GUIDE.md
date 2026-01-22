# Adding a Real Connector

This guide shows how to add a new connector for a real comic/manga site.

## Example: MyComicSite Connector

### Step 1: Create Connector Class

```kotlin
package dev.koenv.chaptervault.connectors.mycomicsite

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.domain.*
import dev.koenv.chaptervault.core.storage.StorageSink
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.delay

class MyComicSiteConnector : Connector {
    
    private val httpClient = HttpClient()
    
    override fun canHandle(url: String): Boolean {
        return url.startsWith("https://mycomicsite.com/")
    }
    
    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        // Make HTTP request to search endpoint
        val response = httpClient.get("https://mycomicsite.com/api/search?q=$query")
        val json = parseJson(response.bodyAsText())
        
        return json.map { item ->
            SeriesSearchResult(
                url = item["url"],
                title = item["title"],
                description = item["description"],
                coverUrl = item["cover"]
            )
        }
    }
    
    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        val response = httpClient.get(seriesUrl)
        val html = response.bodyAsText()
        
        // Parse HTML to extract metadata
        return SeriesMetadata(
            url = seriesUrl,
            title = extractTitle(html),
            description = extractDescription(html),
            author = extractAuthor(html),
            coverUrl = extractCoverUrl(html),
            tags = extractTags(html),
            status = extractStatus(html)
        )
    }
    
    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        val response = httpClient.get("$seriesUrl/chapters")
        val json = parseJson(response.bodyAsText())
        
        return json.map { chapter ->
            ChapterMetadata(
                url = chapter["url"],
                seriesUrl = seriesUrl,
                title = chapter["title"],
                chapterNumber = chapter["number"],
                publishDate = chapter["date"],
                pageCount = chapter["pageCount"]
            )
        }
    }
    
    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        // Step 1: Fetch chapter page to get page URLs
        val response = httpClient.get(chapterUrl)
        val html = response.bodyAsText()
        val pageUrls = extractPageUrls(html)
        
        // Step 2: Download each page
        pageUrls.forEachIndexed { index, pageUrl ->
            // Rate limit per site policy
            delay(500)
            
            // Fetch image bytes
            val imageResponse = httpClient.get(pageUrl)
            val imageBytes = imageResponse.readBytes()
            val mimeType = imageResponse.contentType()?.toString() ?: "image/jpeg"
            
            // Pass to storage
            storage.writePage(index, imageBytes, mimeType)
        }
    }
}
```

### Step 2: Handle Authentication/Tokens (if needed)

```kotlin
class MyComicSiteConnector : Connector {
    
    private var csrfToken: String? = null
    private var sessionCookie: String? = null
    
    private suspend fun ensureAuthenticated() {
        if (csrfToken == null) {
            // Fetch CSRF token
            val response = httpClient.get("https://mycomicsite.com/token")
            csrfToken = response.headers["X-CSRF-Token"]
            sessionCookie = response.headers["Set-Cookie"]
        }
    }
    
    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        ensureAuthenticated()
        
        val response = httpClient.get(chapterUrl) {
            headers {
                append("X-CSRF-Token", csrfToken!!)
                append("Cookie", sessionCookie!!)
            }
        }
        // ... rest of download logic
    }
}
```

### Step 3: Use Browser Automation (for JS-heavy sites)

```kotlin
import com.microsoft.playwright.*

class MyComicSiteConnector : Connector {
    
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var page: Page? = null
    
    private suspend fun ensureBrowserReady() {
        if (playwright == null) {
            playwright = Playwright.create()
            browser = playwright!!.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true)
            )
            page = browser!!.newPage()
        }
    }
    
    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        ensureBrowserReady()
        
        // Navigate to chapter page
        page!!.navigate(chapterUrl)
        
        // Wait for images to load
        page!!.waitForSelector("img.chapter-page")
        
        // Extract page URLs from rendered page
        val pageUrls = page!!.locator("img.chapter-page")
            .allTextContents()
            .map { it.getAttribute("src") }
        
        // Download each page
        pageUrls.forEachIndexed { index, pageUrl ->
            delay(500)
            
            val imageBytes = httpClient.get(pageUrl).readBytes()
            storage.writePage(index, imageBytes, "image/jpeg")
        }
    }
    
    fun cleanup() {
        page?.close()
        browser?.close()
        playwright?.close()
    }
}
```

### Step 4: Register Connector

In `Main.kt`:

```kotlin
fun main() {
    val connectorRegistry = SimpleConnectorRegistry()
    
    // Register mock connector
    connectorRegistry.register(
        MockConnector(),
        RateLimitConfig(minDelay = 500.milliseconds)
    )
    
    // Register real connector with custom rate limits
    connectorRegistry.register(
        MyComicSiteConnector(),
        RateLimitConfig(
            minDelay = 2.seconds,        // 2 seconds between requests
            maxConcurrent = 1,            // One request at a time
            maxRequestsPerWindow = 30,    // 30 requests max
            windowDuration = 60.seconds   // Per minute
        )
    )
    
    // ... rest of setup
}
```

### Step 5: Test Connector

```kotlin
fun main() = runBlocking {
    val connector = MyComicSiteConnector()
    val storage = FileStorageSink(File("/tmp/test"))
    
    // Test search
    val results = connector.searchSeries("one piece")
    println("Found ${results.size} results")
    
    // Test metadata
    val metadata = connector.fetchSeriesMetadata(results.first().url)
    println("Series: ${metadata.title}")
    
    // Test chapter list
    val chapters = connector.fetchChapterList(metadata.url)
    println("Chapters: ${chapters.size}")
    
    // Test download
    storage.beginSeries(metadata)
    storage.beginChapter(chapters.first())
    connector.downloadChapter(chapters.first().url, storage)
    storage.endChapter()
    storage.endSeries()
    
    println("Download complete!")
}
```

## Best Practices

### 1. Respect Site Policies
- Always check robots.txt
- Implement appropriate rate limiting
- Add user-agent headers
- Don't overwhelm servers

### 2. Error Handling
```kotlin
override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
    try {
        // Download logic
    } catch (e: HttpException) {
        if (e.statusCode == 429) {
            // Rate limited - wait and retry
            delay(10.seconds)
            // Retry logic
        } else {
            throw e
        }
    }
}
```

### 3. Caching
```kotlin
class MyComicSiteConnector : Connector {
    private val metadataCache = mutableMapOf<String, SeriesMetadata>()
    
    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        return metadataCache.getOrPut(seriesUrl) {
            // Fetch from network
            fetchMetadataFromNetwork(seriesUrl)
        }
    }
}
```

### 4. Logging
```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

class MyComicSiteConnector : Connector {
    private val logger = KotlinLogging.logger {}
    
    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        logger.info { "Downloading chapter: $chapterUrl" }
        
        try {
            // Download logic
            logger.info { "Chapter downloaded successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to download chapter" }
            throw e
        }
    }
}
```

## Common Patterns

### Pattern 1: Paginated Chapter Lists
```kotlin
override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
    val allChapters = mutableListOf<ChapterMetadata>()
    var page = 1
    
    while (true) {
        val response = httpClient.get("$seriesUrl/chapters?page=$page")
        val chapters = parseChapters(response.bodyAsText())
        
        if (chapters.isEmpty()) break
        
        allChapters.addAll(chapters)
        page++
    }
    
    return allChapters
}
```

### Pattern 2: Dynamic Page Discovery
```kotlin
override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
    // Some sites don't list all pages upfront
    var pageIndex = 0
    var currentUrl = chapterUrl
    
    while (true) {
        val response = httpClient.get(currentUrl)
        val html = response.bodyAsText()
        
        val imageUrl = extractImageUrl(html)
        val imageBytes = httpClient.get(imageUrl).readBytes()
        storage.writePage(pageIndex, imageBytes, "image/jpeg")
        
        val nextUrl = extractNextPageUrl(html) ?: break
        currentUrl = nextUrl
        pageIndex++
    }
}
```

### Pattern 3: VRF Token Handling
```kotlin
override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
    // Fetch chapter page
    val chapterResponse = httpClient.get(chapterUrl)
    val html = chapterResponse.bodyAsText()
    
    // Extract VRF token from JavaScript
    val vrfToken = extractVrfToken(html)
    
    // Use token to get real page URLs
    val pageResponse = httpClient.post("$chapterUrl/pages") {
        setBody("vrf=$vrfToken")
    }
    val pageUrls = parseJson(pageResponse.bodyAsText())
    
    // Download pages
    pageUrls.forEachIndexed { index, url ->
        val imageBytes = httpClient.get(url).readBytes()
        storage.writePage(index, imageBytes, "image/jpeg")
    }
}
```

## Testing

Create a test file:

```kotlin
class MyComicSiteConnectorTest {
    
    @Test
    fun testCanHandle() {
        val connector = MyComicSiteConnector()
        assertTrue(connector.canHandle("https://mycomicsite.com/series/123"))
        assertFalse(connector.canHandle("https://othersite.com/series/123"))
    }
    
    @Test
    fun testSearch() = runBlocking {
        val connector = MyComicSiteConnector()
        val results = connector.searchSeries("test")
        assertNotNull(results)
        assertTrue(results.isNotEmpty())
    }
}
```

## Resources

- [Playwright Documentation](https://playwright.dev/java/docs/intro)
- [Ktor Client](https://ktor.io/docs/getting-started-ktor-client.html)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
