package dev.koenv.chaptervault.core.connector

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.execution.Executor
import dev.koenv.chaptervault.core.execution.ExecutionContext
import dev.koenv.chaptervault.core.storage.StorageSink
import java.net.URI

/**
 * Connector interface for fetching content from sources.
 *
 * Connectors use an Executor internally to run execution plans.
 * This allows connectors to:
 * - Create and execute plans
 * - Chain multiple plans based on intermediate results
 * - Mix browser and HTTP operations as needed
 * - Return actual data (not plans)
 *
 * The Executor handles the actual HTTP/browser operations,
 * enabling local or remote execution.
 */
interface Connector {
    /**
     * Connector configuration.
     */
    val config: ConnectorConfig

    /**
     * Domains this connector handles (simple FQDNs).
     * Example: listOf("manga.example.com", "www.manga.example.com")
     */
    val baseUrls: List<String>

    /**
     * The executor used to run plans.
     */
    val executor: Executor

    /**
     * Check if this connector can handle the given URL.
     * Default implementation extracts the host from the URL and checks against baseUrls.
     */
    fun canHandle(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return baseUrls.any { domain ->
            host.equals(domain, ignoreCase = true)
        }
    }

    /**
     * Extract the host from a URL.
     */
    private fun extractHost(url: String): String? {
        return try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            // Fallback: try simple parsing
            val withoutProtocol = url.removePrefix("https://").removePrefix("http://")
            withoutProtocol.substringBefore("/").substringBefore(":").lowercase().ifEmpty { null }
        }
    }

    /**
     * Extract series URL from a chapter URL.
     * Override this for site-specific URL structures.
     *
     * Default implementation removes the last path segment.
     */
    fun extractSeriesUrl(chapterUrl: String): String {
        return try {
            val uri = URI(chapterUrl)
            val segments = uri.path.split("/").filter { it.isNotEmpty() }
            if (segments.size <= 1) {
                chapterUrl
            } else {
                val seriesPath = segments.dropLast(1).joinToString("/", prefix = "/")
                "${uri.scheme}://${uri.host}$seriesPath"
            }
        } catch (e: Exception) {
            chapterUrl.substringBeforeLast("/")
        }
    }

    /**
     * Get the execution context for this connector.
     * Override to customize context (e.g., add default headers).
     */
    fun getExecutionContext(): ExecutionContext {
        return ExecutionContext(
            connectorName = config.name,
            sessionId = "connector:${config.name}"
        )
    }

    /**
     * Search for series matching the query.
     */
    suspend fun searchSeries(query: String): List<SeriesSearchResult>

    /**
     * Fetch metadata for a series.
     */
    suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata

    /**
     * Fetch the list of chapters for a series.
     */
    suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata>

    /**
     * Download a chapter to storage.
     */
    suspend fun downloadChapter(chapterUrl: String, storage: StorageSink)
}
