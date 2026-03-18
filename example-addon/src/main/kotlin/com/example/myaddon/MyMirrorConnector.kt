package com.example.myaddon

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.execution.Executor
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.storage.StorageSink
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Mirror connector for mirror.example.com.
 *
 * Targets a different domain but shares all scraping logic with [MyConnector]
 * by delegating to it with [AddonConfig.mirrorUrl] as the base URL.
 * This is a common pattern when a site has regional mirrors or fallback domains.
 */
class MyMirrorConnector(
    executor: Executor,
    addonConfig: AddonConfig,
) : Connector {

    // Delegate all scraping to MyConnector, re-configured with the mirror URL.
    // Any change to MyConnector's extraction logic automatically applies here too.
    private val delegate = MyConnector(executor, addonConfig.copy(baseUrl = addonConfig.mirrorUrl))

    override val executor: Executor get() = delegate.executor

    override val config = ConnectorConfig(
        id = "my-connector-mirror",
        name = "My Connector (Mirror)",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 500.milliseconds,
            maxConcurrent = 2,
            maxRequestsPerWindow = 60,
            windowDuration = 60.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = addonConfig.apiKey != null,
            supportsBatchDownload = true,
            supportsPageCount = false,
            maxConcurrentDownloads = 3
        )
    )

    override val baseUrls = listOf("mirror.example.com")

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> =
        delegate.searchSeries(query)

    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata =
        delegate.fetchSeriesMetadata(seriesUrl)

    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> =
        delegate.fetchChapterList(seriesUrl)

    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) =
        delegate.downloadChapter(chapterUrl, storage)
}
