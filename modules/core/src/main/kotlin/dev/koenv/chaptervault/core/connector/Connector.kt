package dev.koenv.chaptervault.core.connector

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.storage.StorageSink
import java.net.URI

interface Connector {
    val config: ConnectorConfig
    val baseUrls: List<String>

    /**
     * Check if this connector can handle the given URL.
     * Default implementation uses glob-style pattern matching against baseUrls.
     */
    fun canHandle(url: String): Boolean {
        return baseUrls.any { pattern ->
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .toRegex()
            regex.matches(url)
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
            // Fallback: simple string manipulation
            chapterUrl.substringBeforeLast("/")
        }
    }

    suspend fun searchSeries(query: String): List<SeriesSearchResult>
    suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata
    suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata>
    suspend fun downloadChapter(chapterUrl: String, storage: StorageSink)
}
