package com.example.mymodule

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.execution.ActionResult
import dev.koenv.chaptervault.core.execution.BulkDownloadResult
import dev.koenv.chaptervault.core.execution.ExecutionContext
import dev.koenv.chaptervault.core.execution.ExecutionException
import dev.koenv.chaptervault.core.execution.Executor
import dev.koenv.chaptervault.core.execution.ExtractedDataResult
import dev.koenv.chaptervault.core.execution.executionPlan
import dev.koenv.chaptervault.core.execution.requireSuccess
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.storage.StorageSink
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Main connector for example.com.
 *
 * Receives its configuration from [MyModule] via [moduleConfig], so the module
 * acts as the single point of configuration for all connectors it registers.
 * Use [moduleConfig.executor] for all network requests — never create your own HTTP client.
 */
open class MyConnector(
    override val executor: Executor,
    protected val moduleConfig: ModuleConfig,
) : Connector {

    override val config = ConnectorConfig(
        id = "my-connector",
        name = "My Connector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 500.milliseconds,
            maxConcurrent = 2,
            maxRequestsPerWindow = 60,
            windowDuration = 60.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = moduleConfig.apiKey != null,
            supportsBatchDownload = true,
            supportsPageCount = false,
            maxConcurrentDownloads = 3
        )
    )

    override val baseUrls = listOf("example.com", "www.example.com")

    /**
     * Inject module-level headers (e.g. API key) into every request made by this connector.
     * Override this in subclasses to add additional context.
     */
    override fun getExecutionContext(): ExecutionContext {
        val base = super.getExecutionContext()
        return if (moduleConfig.apiKey != null) {
            base.copy(defaultHeaders = base.defaultHeaders + mapOf("X-API-Key" to moduleConfig.apiKey))
        } else {
            base
        }
    }

    // ========================================================================
    // Search
    // ========================================================================

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val plan = executionPlan {
            extractData(url = "${moduleConfig.baseUrl}/search?q=$encodedQuery", id = "search") {
                nestedList("results", ".manga-card") {
                    href("url", "a")
                    text("title", ".title")
                    src("coverUrl", "img")
                    text("description", ".description")
                }
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val extracted = results["search"] as? ExtractedDataResult
        if (extracted?.success != true) return emptyList()

        return extracted.getObjectList("results")?.mapNotNull { item ->
            val url = item["url"] as? String ?: return@mapNotNull null
            SeriesSearchResult(
                url = url,
                title = item["title"] as? String ?: "Unknown",
                externalId = url.trimEnd('/').substringAfterLast("/"),
                description = item["description"] as? String,
                coverUrl = item["coverUrl"] as? String
            )
        } ?: emptyList()
    }

    // ========================================================================
    // Series Metadata
    // ========================================================================

    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        val plan = executionPlan {
            extractData(url = seriesUrl, id = "series") {
                text("title", "h1")
                text("author", ".author")
                text("description", ".synopsis")
                src("coverUrl", ".cover img")
                textList("tags", ".genre a")
                text("status", ".status")
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val extracted = (results["series"] as? ExtractedDataResult)?.requireSuccess()
            ?: throw ExecutionException("Failed to fetch series", ActionResult.failure("series", "No result"))

        return SeriesMetadata(
            url = seriesUrl,
            title = extracted.getString("title") ?: "Unknown",
            externalId = seriesUrl.trimEnd('/').substringAfterLast("/"),
            description = extracted.getString("description"),
            author = extracted.getString("author"),
            coverUrl = extracted.getString("coverUrl"),
            tags = extracted.getStringList("tags") ?: emptyList(),
            status = when {
                extracted.getString("status")?.contains("ongoing", ignoreCase = true) == true -> SeriesStatus.ONGOING
                extracted.getString("status")?.contains("completed", ignoreCase = true) == true -> SeriesStatus.COMPLETED
                else -> SeriesStatus.UNKNOWN
            }
        )
    }

    // ========================================================================
    // Chapter List
    // ========================================================================

    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        val plan = executionPlan {
            extractData(url = seriesUrl, id = "chapters") {
                nestedList("chapters", ".chapter-list li") {
                    href("url", "a")
                    text("title", "a")
                    text("number", ".chapter-number")
                }
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        val extracted = (results["chapters"] as? ExtractedDataResult)?.requireSuccess()
            ?: throw ExecutionException("Failed to fetch chapters", ActionResult.failure("chapters", "No result"))

        return extracted.getObjectList("chapters")?.mapNotNull { item ->
            val url = item["url"] as? String ?: return@mapNotNull null
            val number = item["number"] as? String ?: ""
            ChapterMetadata(
                url = url,
                seriesUrl = seriesUrl,
                title = item["title"] as? String ?: "Chapter $number",
                chapterNumber = number,
                externalId = url.trimEnd('/').substringAfterLast("/"),
                chapterIndex = number.toFloatOrNull()?.times(1000)?.toInt(),
                publishDate = null,
                pageCount = null
            )
        } ?: emptyList()
    }

    // ========================================================================
    // Download Chapter
    // ========================================================================

    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        val readerPlan = executionPlan {
            extractData(url = chapterUrl, id = "reader") {
                textList("pageUrls", "img.page", attribute = "src")
            }
        }

        val readerResults = executor.executeAll(readerPlan.instructions, getExecutionContext())
        val readerData = (readerResults["reader"] as? ExtractedDataResult)?.requireSuccess()
            ?: throw ExecutionException("Failed to fetch reader", ActionResult.failure("reader", "No result"))

        val pageUrls = readerData.getStringList("pageUrls")?.filter { it.isNotBlank() }
            ?: throw IllegalStateException("No pages found for chapter: $chapterUrl")

        val downloadPlan = executionPlan {
            bulkDownload(retries = 2, id = "pages") {
                pageUrls.forEachIndexed { index, url ->
                    // Tag with a rate-limit bucket to cap CDN concurrency independently
                    item("page-$index", url, referer = chapterUrl, rateLimitBucket = "cdn")
                }
            }
        }

        val downloadResults = executor.executeAll(downloadPlan.instructions, getExecutionContext())
        val bulkResult = downloadResults["pages"] as? BulkDownloadResult
            ?: throw ExecutionException("Failed to download pages", ActionResult.failure("pages", "No result"))

        if (bulkResult.failureCount > 0) {
            logger.warn { "Downloaded ${bulkResult.successCount}/${pageUrls.size} pages for $chapterUrl — ${bulkResult.failureCount} failed" }
        }

        bulkResult.forEachSuccess { id, bytes, mimeType ->
            val index = id.removePrefix("page-").toInt()
            storage.writePage(index, bytes, mimeType ?: "image/jpeg")
        }
    }
}
