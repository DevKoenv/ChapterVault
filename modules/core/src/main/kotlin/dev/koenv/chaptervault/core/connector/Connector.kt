package dev.koenv.chaptervault.core.connector

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.storage.StorageSink

interface Connector {
    val config: ConnectorConfig
    val baseUrls: List<String>
    
    fun canHandle(url: String): Boolean
    
    suspend fun searchSeries(query: String): List<SeriesSearchResult>
    suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata
    suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata>
    suspend fun downloadChapter(chapterUrl: String, storage: StorageSink)
}

fun Connector.defaultCanHandle(url: String): Boolean {
    return baseUrls.any { pattern ->
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .toRegex()
        regex.matches(url)
    }
}
