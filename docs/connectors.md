# Connector Development Guide

This guide explains how to create connectors for ChapterVault to support new content sources.

## Overview

A **Connector** is responsible for:

- Determining if it can handle a URL
- Searching for series
- Fetching series metadata
- Fetching chapter lists
- Downloading chapter content

## Architecture

```
User Request → Orchestrator → Connector → Executor → Source
                    ↓              ↓
               Rate Limiter   Execution Plan
```

Connectors create **Execution Plans** that describe what data to fetch. The **Executor** handles the actual HTTP requests and browser automation.

## Creating a Basic Connector

### Step 1: Create the Connector Class

Create a new file in `modules/connectors/src/main/kotlin/dev/koenv/chaptervault/connectors/impl/`:

```kotlin
package dev.koenv.chaptervault.connectors.impl

import dev.koenv.chaptervault.core.config.ConnectorSpecificConfig
import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.*
import dev.koenv.chaptervault.core.execution.*
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.storage.StorageSink
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MyConnector(
    override val executor: Executor,
    private val connectorConfig: ConnectorSpecificConfig? = null
) : Connector {

    override val config = ConnectorConfig(
        id = "my-connector",
        name = "My Connector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = connectorConfig?.rateLimit?.minDelayMillis?.milliseconds ?: 500.milliseconds,
            maxConcurrent = connectorConfig?.rateLimit?.maxConcurrent ?: 2,
            maxRequestsPerWindow = connectorConfig?.rateLimit?.maxRequestsPerMinute ?: 60,
            windowDuration = 60.seconds
        ),
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
        "manga.example.com",
        "www.manga.example.com"
    )

    // Implement required methods...
}
```

### Step 2: Implement Search

Use the `extractData` DSL for declarative extraction:

```kotlin
override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
    val searchUrl = "https://manga.example.com/search?q=${encodeUrl(query)}"

    val plan = executionPlan {
        extractData(url = searchUrl, id = "search") {
            nestedList("results", ".search-result") {
                href("url", "a.title-link")
                text("title", ".title")
                text("description", ".description")
                src("coverUrl", "img.cover")
            }
        }
    }

    val results = executor.executeAll(plan.instructions, getExecutionContext())
    val extracted = results["search"] as? ExtractedDataResult

    if (extracted?.success != true) {
        return emptyList()
    }

    return extracted.getObjectList("results")?.mapNotNull { item ->
        val url = item["url"] as? String ?: return@mapNotNull null
        SeriesSearchResult(
            url = url,
            title = item["title"] as? String ?: "Unknown",
            description = item["description"] as? String,
            coverUrl = item["coverUrl"] as? String
        )
    } ?: emptyList()
}
```

### Step 3: Implement Metadata Fetching

```kotlin
override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
    val plan = executionPlan {
        extractData(url = seriesUrl, id = "series") {
            text("title", "h1.series-title")
            text("author", ".author-name")
            text("description", ".synopsis")
            src("coverUrl", "img.series-cover")
            textList("tags", ".genre-tag")
            text("status", ".series-status")
        }
    }

    val results = executor.executeAll(plan.instructions, getExecutionContext())
    val extracted = (results["series"] as? ExtractedDataResult)
        ?: throw ExecutionException("Failed to fetch series", ActionResult.failure("series", "No result"))

    return SeriesMetadata(
        url = seriesUrl,
        title = extracted.getString("title") ?: "Unknown",
        description = extracted.getString("description"),
        author = extracted.getString("author"),
        coverUrl = extracted.getString("coverUrl"),
        tags = extracted.getStringList("tags") ?: emptyList(),
        status = parseStatus(extracted.getString("status") ?: "")
    )
}

private fun parseStatus(status: String): SeriesStatus {
    return when {
        "ongoing" in status.lowercase() -> SeriesStatus.ONGOING
        "completed" in status.lowercase() -> SeriesStatus.COMPLETED
        "hiatus" in status.lowercase() -> SeriesStatus.HIATUS
        else -> SeriesStatus.UNKNOWN
    }
}
```

### Step 4: Implement Chapter List

```kotlin
override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
    val plan = executionPlan {
        extractData(url = seriesUrl, id = "chapters") {
            nestedList("chapters", ".chapter-item") {
                href("url", "a")
                text("title", ".chapter-title")
                text("number", ".chapter-number")
                text("date", "time", attribute = "datetime")
            }
        }
    }

    val results = executor.executeAll(plan.instructions, getExecutionContext())
    val extracted = results["chapters"] as? ExtractedDataResult
        ?: throw ExecutionException("Failed to fetch chapters", ActionResult.failure("chapters", "No result"))

    return extracted.getObjectList("chapters")?.mapNotNull { item ->
        val url = item["url"] as? String ?: return@mapNotNull null
        ChapterMetadata(
            url = url,
            seriesUrl = seriesUrl,
            title = item["title"] as? String ?: "Unknown",
            chapterNumber = item["number"] as? String ?: "0",
            publishDate = item["date"] as? String,
            pageCount = null
        )
    }?.sortedBy { it.chapterNumber.toDoubleOrNull() ?: 0.0 } ?: emptyList()
}
```

### Step 5: Implement Download

Use `bulkDownload` for efficient concurrent downloads:

```kotlin
override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
    // Step 1: Get page URLs
    val plan = executionPlan {
        extractData(url = chapterUrl, id = "reader") {
            textList("pageUrls", "img.reader-page", attribute = "src")
        }
    }

    val results = executor.executeAll(plan.instructions, getExecutionContext())
    val readerData = results["reader"] as? ExtractedDataResult
        ?: throw ExecutionException("Failed to fetch reader", ActionResult.failure("reader", "No result"))

    val pageUrls = readerData.getStringList("pageUrls")
        ?.filter { it.isNotBlank() }
        ?: throw IllegalStateException("No pages found")

    // Step 2: Download pages concurrently
    val downloadPlan = executionPlan {
        bulkDownload(maxConcurrency = 3, retries = 2, id = "pages") {
            pageUrls.forEachIndexed { index, url ->
                item("page-$index", url, referer = chapterUrl)
            }
        }
    }

    val downloadResults = executor.executeAll(downloadPlan.instructions, getExecutionContext())
    val bulkResult = downloadResults["pages"] as? BulkDownloadResult
        ?: throw ExecutionException("Download failed", ActionResult.failure("pages", "No result"))

    // Step 3: Write to storage
    bulkResult.forEachSuccess { id, bytes, mimeType ->
        val index = id.removePrefix("page-").toInt()
        storage.writePage(index, bytes, mimeType ?: "image/jpeg")
    }
}
```

## Extraction DSL Reference

### Basic Extractors

```kotlin
extractData(url = "...") {
    // Single text value
    text("fieldName", "css-selector")
    text("fieldName", "css-selector", attribute = "data-value")

    // Single href (resolved to absolute URL)
    href("fieldName", "a.link")

    // Single src (resolved to absolute URL)
    src("fieldName", "img")

    // List of text values
    textList("fieldName", ".items")
    textList("fieldName", ".items", attribute = "href")
}
```

### Nested Extraction

```kotlin
extractData(url = "...") {
    // Single nested object
    nested("author", ".author-info") {
        text("name", ".name")
        href("url", "a")
    }

    // List of nested objects (most common)
    nestedList("chapters", ".chapter-item") {
        href("url", "a")
        text("title", ".title")
        text("number", ".number")
    }
}
```

## Browser Automation

For JavaScript-heavy sites, use browser instructions:

```kotlin
override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
    val plan = executionPlan {
        browser {
            navigate("https://js-heavy-site.com/search")
            waitForSelector("input.search-box")
            fill("input.search-box", query)
            click("button.search-submit")
            waitForSelector(".results-loaded")
            queryAll(".search-result", id = "results")
        }
    }

    val results = executor.executeAll(plan.instructions, getExecutionContext())
    val elementsResult = results["results"] as? ElementsResult

    return elementsResult?.elements?.mapNotNull { element ->
        SeriesSearchResult(
            url = element.href() ?: return@mapNotNull null,
            title = element.textContent ?: "Unknown",
            coverUrl = element.src()
        )
    } ?: emptyList()
}

override fun getExecutionContext(): ExecutionContext {
    return ExecutionContext(
        connectorName = config.name,
        sessionId = "connector:${config.name}",
        useBrowser = true  // Enable browser for this connector
    )
}
```

## Best Practices

### 1. Use Declarative Extraction

Prefer `extractData` over manual Jsoup parsing:

```kotlin
// Good
extractData(url = searchUrl) {
    nestedList("results", ".item") {
        href("url", "a")
        text("title", ".title")
    }
}

// Avoid
val html = fetchHtml(searchUrl)
val doc = Jsoup.parse(html)
doc.select(".item").mapNotNull { ... }
```

### 2. Handle Missing Data Gracefully

```kotlin
val title = extracted.getString("title") ?: "Unknown"
val description = extracted.getString("description")  // Can be null
```

### 3. Respect Rate Limits

Configure appropriate delays:

```kotlin
rateLimitConfig = RateLimitConfig(
    minDelay = 1.seconds,        // Don't hammer the server
    maxConcurrent = 2,           // Limit parallel requests
    maxRequestsPerWindow = 30,   // 30 requests per minute
    windowDuration = 60.seconds
)
```

### 4. Use bulkDownload for Pages

```kotlin
// Good - concurrent with retry
bulkDownload(maxConcurrency = 3, retries = 2) {
    pageUrls.forEachIndexed { i, url ->
        item("page-$i", url, referer = chapterUrl)
    }
}

// Avoid - sequential, no retry
pageUrls.forEachIndexed { i, url ->
    fetchBytes(url, id = "page-$i")
}
```

### 5. Log Important Events

```kotlin
private val logger = LoggerFactory.getLogger(MyConnector::class.java)

override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
    logger.info("Searching for: {}", query)
    // ...
    logger.info("Found {} results", results.size)
    return results
}
```

## Testing Connectors

Create tests in `modules/connectors/src/test/kotlin/`:

```kotlin
class MyConnectorTest {
    private lateinit var connector: MyConnector
    private val mockExecutor = mockk<Executor>()

    @BeforeEach
    fun setup() {
        connector = MyConnector(mockExecutor)
    }

    @Test
    fun `canHandle returns true for supported domains`() {
        assertTrue(connector.canHandle("https://manga.example.com/series/test"))
        assertTrue(connector.canHandle("https://www.manga.example.com/series/test"))
        assertFalse(connector.canHandle("https://other-site.com/series/test"))
    }

    @Test
    fun `searchSeries returns results`() = runTest {
        // Setup mock
        coEvery { mockExecutor.executeAll(any(), any()) } returns mapOf(
            "search" to ExtractedDataResult.success(
                "search",
                mapOf(
                    "results" to listOf(
                        mapOf("url" to "...", "title" to "Test")
                    )
                ),
                "https://manga.example.com/search",
                200
            )
        )

        // Execute
        val results = connector.searchSeries("test")

        // Verify
        assertEquals(1, results.size)
        assertEquals("Test", results[0].title)
    }
}
```

## Registering Connectors

Add your connector to the registry in the app module:

```kotlin
// In Main.kt or a dedicated registration function
val connectorRegistry = ConnectorRegistryImpl()
connectorRegistry.register(MyConnector(executor))
```

Or use Kotlin's ServiceLoader for automatic registration.

## Common Patterns

### Pagination

```kotlin
override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
    val allChapters = mutableListOf<ChapterMetadata>()
    var page = 1

    while (true) {
        val plan = executionPlan {
            extractData(url = "$seriesUrl?page=$page", id = "chapters") {
                nestedList("chapters", ".chapter-item") { ... }
                text("hasNext", ".pagination .next")
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val extracted = results["chapters"] as? ExtractedDataResult ?: break

        val chapters = extracted.getObjectList("chapters") ?: emptyList()
        if (chapters.isEmpty()) break

        allChapters.addAll(chapters.mapNotNull { ... })

        if (extracted.getString("hasNext") == null) break
        page++
    }

    return allChapters
}
```

### Authentication

```kotlin
override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
    val authHeaders = connectorConfig?.auth?.let { auth ->
        mapOf("Authorization" to "Bearer ${auth.token}")
    } ?: emptyMap()

    val plan = executionPlan {
        extractData(
            url = searchUrl,
            headers = authHeaders,  // Pass auth headers
            id = "search"
        ) { ... }
    }
    // ...
}
```

### Cloudflare Bypass

For sites behind Cloudflare, use browser automation:

```kotlin
override fun getExecutionContext() = ExecutionContext(
    connectorName = config.name,
    sessionId = "connector:${config.name}",
    useBrowser = true  // Browser handles Cloudflare challenges
)
```
