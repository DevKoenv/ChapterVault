package dev.koenv.chaptervault.orchestration.engine

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.storage.StorageSink
import dev.koenv.chaptervault.core.repository.TaskStatus as DbTaskStatus
import dev.koenv.chaptervault.core.repository.TaskRepositoryPort
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.orchestration.ratelimit.OrchestratorRateLimiterStatus
import dev.koenv.chaptervault.orchestration.ratelimit.RateLimiter
import dev.koenv.chaptervault.orchestration.task.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * Orchestrator manages task execution, rate limiting, and retries.
 *
 * Key responsibilities:
 * - Accept high-level tasks (browse, download)
 * - Find appropriate connector
 * - Enforce rate limits
 * - Manage retries and errors
 * - Report progress
 * - Cache metadata in database (when repositories provided)
 *
 * Orchestrator NEVER fetches or parses data directly.
 */
class Orchestrator(
    private val connectorRegistry: ConnectorRegistry,
    private val storageSink: StorageSink,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val seriesRepository: SeriesRepositoryPort? = null,
    private val chapterRepository: ChapterRepositoryPort? = null,
    private val taskRepository: TaskRepositoryPort? = null
) {

    private val logger = LoggerFactory.getLogger(Orchestrator::class.java)
    private val progressMap = mutableMapOf<String, TaskProgress>()
    private val progressMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Search for series across all connectors or a specific connector.
     * Caches search results per-connector in the database for future reference.
     * @param query The search query
     * @param connectorName Optional connector name to search only that connector
     */
    suspend fun searchSeries(query: String, connectorName: String? = null): List<SeriesSearchResult> {
        val taskId = Uuid.random().toString()
        logger.info("Starting search for: {} (connector: {})", query, connectorName ?: "all")
        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Searching for '$query'"))

        return try {
            val results = mutableListOf<SeriesSearchResult>()

            // Determine which connectors to search
            val connectorsToSearch = if (connectorName != null) {
                val connector = connectorRegistry.findById(connectorName)
                if (connector != null) listOf(connector) else emptyList()
            } else {
                connectorRegistry.getAllConnectors()
            }

            // Try selected connectors that support search
            connectorsToSearch.forEach { connector ->
                try {
                    val connectorResults = withRateLimit(connector) {
                        connector.searchSeries(query)
                    }
                    results.addAll(connectorResults)

                    // Cache per-connector immediately
                    if (connectorResults.isNotEmpty() && seriesRepository != null) {
                        try {
                            seriesRepository.upsertAllFromSearch(connectorResults, connector.config.id)
                            logger.debug("Cached {} search results for connector {}", connectorResults.size, connector.config.id)
                        } catch (e: Exception) {
                            logger.warn("Failed to cache search results for connector {}: {}", connector.config.id, e.message)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Connector {} failed to search: {}", connector::class.simpleName, e.message)
                    // Continue with other connectors if one fails
                }
            }

            logger.info("Search completed: found {} results for '{}'", results.size, query)
            updateProgress(TaskProgress(taskId, TaskStatus.COMPLETED, "Found ${results.size} results"))
            results
        } catch (e: Exception) {
            logger.error("Search failed for '{}': {}", query, e.message, e)
            updateProgress(TaskProgress(taskId, TaskStatus.FAILED, error = e.message))
            throw e
        }
    }

    /**
     * Fetch series metadata
     */
    suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        val taskId = Uuid.random().toString()
        logger.info("Fetching series metadata: {}", seriesUrl)
        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Fetching series metadata"))

        return try {
            val connector = findConnectorOrThrow(seriesUrl)
            val metadata = withRateLimit(connector) {
                connector.fetchSeriesMetadata(seriesUrl)
            }
            logger.info("Fetched metadata for series: {}", metadata.title)
            updateProgress(TaskProgress(taskId, TaskStatus.COMPLETED, "Fetched metadata for ${metadata.title}"))
            metadata
        } catch (e: Exception) {
            logger.error("Failed to fetch series metadata for {}: {}", seriesUrl, e.message, e)
            updateProgress(TaskProgress(taskId, TaskStatus.FAILED, error = e.message))
            throw e
        }
    }

    /**
     * Fetch chapter list for a series
     */
    suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        val taskId = Uuid.random().toString()
        logger.info("Fetching chapter list: {}", seriesUrl)
        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Fetching chapter list"))

        return try {
            val connector = findConnectorOrThrow(seriesUrl)
            val chapters = withRateLimit(connector) {
                connector.fetchChapterList(seriesUrl)
            }
            logger.info("Found {} chapters for {}", chapters.size, seriesUrl)
            updateProgress(TaskProgress(taskId, TaskStatus.COMPLETED, "Found ${chapters.size} chapters"))
            chapters
        } catch (e: Exception) {
            updateProgress(TaskProgress(taskId, TaskStatus.FAILED, error = e.message))
            throw e
        }
    }

    /**
     * Download a single chapter
     */
    suspend fun downloadChapter(chapterUrl: String): String {
        val taskId = Uuid.random().toString()
        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Starting chapter download"))

        scope.launch {
            try {
                val connector = findConnectorOrThrow(chapterUrl)

                // Fetch chapter metadata first
                val seriesUrl = extractSeriesUrl(connector, chapterUrl)
                val chapters = withRateLimit(connector) {
                    connector.fetchChapterList(seriesUrl)
                }
                val chapterMetadata = chapters.firstOrNull { it.url == chapterUrl }
                    ?: throw IllegalArgumentException("Chapter not found: $chapterUrl")

                // Fetch series metadata for storage
                val seriesMetadata = withRateLimit(connector) {
                    connector.fetchSeriesMetadata(seriesUrl)
                }

                // Begin storage operations
                storageSink.beginSeries(seriesMetadata)
                storageSink.beginChapter(chapterMetadata)

                updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Downloading pages", 0, 1))

                // Download chapter (connector fetches pages and passes to storage)
                withRateLimit(connector) {
                    connector.downloadChapter(chapterUrl, storageSink)
                }

                // End storage operations
                storageSink.endChapter()
                storageSink.endSeries()

                updateProgress(TaskProgress(taskId, TaskStatus.COMPLETED, "Chapter downloaded successfully"))
            } catch (e: Exception) {
                updateProgress(TaskProgress(taskId, TaskStatus.FAILED, error = e.message))
            }
        }

        return taskId
    }

    /**
     * Download entire series (all chapters)
     * @param seriesUrl The URL of the series to download
     * @param persistedTaskId Optional ID of a persisted task to update progress on
     */
    suspend fun downloadSeries(seriesUrl: String, persistedTaskId: UUID? = null): String {
        val taskId = persistedTaskId?.toString() ?: Uuid.random().toString()
        logger.info("[Task {}] Starting series download for URL: {}", taskId, seriesUrl)

        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Starting series download"))
        updatePersistedTask(persistedTaskId, DbTaskStatus.RUNNING, "Starting series download", 0, 0)

        scope.launch {
            try {
                logger.debug("[Task {}] Finding connector for URL: {}", taskId, seriesUrl)
                val connector = findConnectorOrThrow(seriesUrl)
                logger.info("[Task {}] Using connector: {}", taskId, connector.config.name)

                // Fetch series metadata
                logger.debug("[Task {}] Fetching series metadata...", taskId)
                val seriesMetadata = withRateLimit(connector) {
                    connector.fetchSeriesMetadata(seriesUrl)
                }
                logger.info("[Task {}] Fetched metadata for: {}", taskId, seriesMetadata.title)

                // Save series to database and add to library
                var cachedSeries = seriesRepository?.upsert(seriesMetadata, connector.config.id)
                val seriesId = cachedSeries?.id

                // Auto-add to library on download
                if (seriesId != null && cachedSeries != null && !cachedSeries.inLibrary) {
                    try {
                        cachedSeries = seriesRepository?.addToLibrary(seriesId)
                        logger.info("[Task {}] Added series to library: {}", taskId, seriesId)
                    } catch (e: Exception) {
                        logger.warn("[Task {}] Failed to add series to library: {}", taskId, e.message)
                    }
                }
                logger.info("[Task {}] Saved series to database with ID: {}", taskId, seriesId)

                // Fetch chapter list
                logger.debug("[Task {}] Fetching chapter list...", taskId)
                val chapters = withRateLimit(connector) {
                    connector.fetchChapterList(seriesUrl)
                }
                logger.info("[Task {}] Found {} chapters", taskId, chapters.size)

                // Save chapters to database
                val cachedChapters = if (seriesId != null) {
                    chapterRepository?.saveAll(chapters, seriesId, connector.config.id).also {
                        seriesRepository?.stampChaptersFetchedAt(seriesId)
                    }
                } else null
                logger.info("[Task {}] Saved {} chapters to database", taskId, cachedChapters?.size ?: 0)

                val message = "Downloading ${chapters.size} chapters"
                updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, message, 0, chapters.size))
                updatePersistedTask(persistedTaskId, DbTaskStatus.RUNNING, message, 0, chapters.size)

                // Begin series
                logger.debug("[Task {}] Beginning series storage...", taskId)
                storageSink.beginSeries(seriesMetadata)

                // Download each chapter
                chapters.forEachIndexed { index, chapterMetadata ->
                    val chapterMessage = "Downloading chapter ${index + 1}/${chapters.size}: ${chapterMetadata.title}"
                    logger.info("[Task {}] {}", taskId, chapterMessage)

                    updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, chapterMessage, index, chapters.size))
                    updatePersistedTask(persistedTaskId, DbTaskStatus.RUNNING, chapterMessage, index, chapters.size)

                    // Find the cached chapter to update later
                    val cachedChapter = cachedChapters?.find { it.sourceUrl == chapterMetadata.url }

                    // Mark chapter as downloading
                    cachedChapter?.let { chapterRepository?.markDownloading(it.id) }

                    storageSink.beginChapter(chapterMetadata)

                    withRateLimit(connector) {
                        connector.downloadChapter(chapterMetadata.url, storageSink)
                    }

                    storageSink.endChapter()

                    // Update chapter with file info
                    val filePath = storageSink.getLastWrittenPath()
                    val fileSize = storageSink.getLastWrittenSize()
                    if (cachedChapter != null && filePath != null && fileSize != null) {
                        chapterRepository?.markDownloaded(cachedChapter.id, filePath, fileSize, "CBZ")
                        logger.debug("[Task {}] Updated chapter {} with file: {}", taskId, cachedChapter.id, filePath)
                    }

                    logger.debug("[Task {}] Completed chapter {}/{}", taskId, index + 1, chapters.size)
                }

                // End series
                storageSink.endSeries()

                val completedMessage = "Series downloaded successfully: ${seriesMetadata.title}"
                logger.info("[Task {}] {}", taskId, completedMessage)
                updateProgress(TaskProgress(taskId, TaskStatus.COMPLETED, completedMessage, chapters.size, chapters.size))
                updatePersistedTaskCompleted(persistedTaskId)

            } catch (e: Exception) {
                val errorMessage = "Download failed: ${e.message}"
                logger.error("[Task {}] {}", taskId, errorMessage, e)
                updateProgress(TaskProgress(taskId, TaskStatus.FAILED, error = e.message))
                updatePersistedTaskFailed(persistedTaskId, e.message)
            }
        }

        return taskId
    }

    /**
     * Download specific chapters by their database IDs.
     * @param seriesId The database ID of the series
     * @param chapterIds The database IDs of chapters to download
     * @param persistedTaskId Optional ID of a persisted task to update progress on
     */
    suspend fun downloadChapters(seriesId: UUID, chapterIds: List<UUID>, persistedTaskId: UUID? = null): String {
        val taskId = persistedTaskId?.toString() ?: Uuid.random().toString()
        logger.info("[Task {}] Starting download of {} specific chapters for series {}", taskId, chapterIds.size, seriesId)

        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Starting chapter download"))
        updatePersistedTask(persistedTaskId, DbTaskStatus.RUNNING, "Starting chapter download", 0, chapterIds.size)

        scope.launch {
            try {
                // Get series from database
                var cachedSeries = seriesRepository?.findById(seriesId)
                    ?: throw IllegalArgumentException("Series not found in database: $seriesId")

                // Auto-add to library on download
                if (!cachedSeries.inLibrary && seriesRepository != null) {
                    try {
                        cachedSeries = seriesRepository.addToLibrary(seriesId)
                        logger.info("[Task {}] Added series to library: {}", taskId, seriesId)
                    } catch (e: Exception) {
                        logger.warn("[Task {}] Failed to add series to library: {}", taskId, e.message)
                    }
                }

                // Find connector for the series URL
                logger.debug("[Task {}] Finding connector for series URL: {}", taskId, cachedSeries.sourceUrl)
                val connector = findConnectorOrThrow(cachedSeries.sourceUrl)
                logger.info("[Task {}] Using connector: {}", taskId, connector.config.name)

                // Fetch series metadata for storage
                val seriesMetadata = withRateLimit(connector) {
                    connector.fetchSeriesMetadata(cachedSeries.sourceUrl)
                }

                // Get chapters from database
                val chaptersToDownload = chapterIds.mapNotNull { chapterId ->
                    chapterRepository?.findById(chapterId)
                }

                if (chaptersToDownload.isEmpty()) {
                    throw IllegalArgumentException("No valid chapters found to download")
                }

                logger.info("[Task {}] Found {} chapters to download", taskId, chaptersToDownload.size)

                val message = "Downloading ${chaptersToDownload.size} chapters"
                updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, message, 0, chaptersToDownload.size))
                updatePersistedTask(persistedTaskId, DbTaskStatus.RUNNING, message, 0, chaptersToDownload.size)

                // Begin series storage
                storageSink.beginSeries(seriesMetadata)

                // Download each chapter
                chaptersToDownload.forEachIndexed { index, cachedChapter ->
                    val chapterMessage = "Downloading chapter ${index + 1}/${chaptersToDownload.size}: ${cachedChapter.title}"
                    logger.info("[Task {}] {}", taskId, chapterMessage)

                    updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, chapterMessage, index, chaptersToDownload.size))
                    updatePersistedTask(persistedTaskId, DbTaskStatus.RUNNING, chapterMessage, index, chaptersToDownload.size)

                    // Mark chapter as downloading
                    chapterRepository?.markDownloading(cachedChapter.id)

                    // Create ChapterMetadata for storage
                    val chapterMetadata = dev.koenv.chaptervault.core.domain.ChapterMetadata(
                        url = cachedChapter.sourceUrl,
                        seriesUrl = cachedSeries.sourceUrl,
                        title = cachedChapter.title,
                        chapterNumber = cachedChapter.chapterNumber,
                        externalId = cachedChapter.externalId,
                        chapterIndex = cachedChapter.chapterIndex,
                        publishDate = cachedChapter.publishDate,
                        pageCount = cachedChapter.pageCount
                    )

                    storageSink.beginChapter(chapterMetadata)

                    withRateLimit(connector) {
                        connector.downloadChapter(cachedChapter.sourceUrl, storageSink)
                    }

                    storageSink.endChapter()

                    // Update chapter with file info
                    val filePath = storageSink.getLastWrittenPath()
                    val fileSize = storageSink.getLastWrittenSize()
                    if (filePath != null && fileSize != null) {
                        chapterRepository?.markDownloaded(cachedChapter.id, filePath, fileSize, "CBZ")
                        logger.debug("[Task {}] Updated chapter {} with file: {}", taskId, cachedChapter.id, filePath)
                    }

                    logger.debug("[Task {}] Completed chapter {}/{}", taskId, index + 1, chaptersToDownload.size)
                }

                // End series
                storageSink.endSeries()

                val completedMessage = "Downloaded ${chaptersToDownload.size} chapters successfully"
                logger.info("[Task {}] {}", taskId, completedMessage)
                updateProgress(TaskProgress(taskId, TaskStatus.COMPLETED, completedMessage, chaptersToDownload.size, chaptersToDownload.size))
                updatePersistedTaskCompleted(persistedTaskId)

            } catch (e: Exception) {
                val errorMessage = "Chapter download failed: ${e.message}"
                logger.error("[Task {}] {}", taskId, errorMessage, e)
                updateProgress(TaskProgress(taskId, TaskStatus.FAILED, error = e.message))
                updatePersistedTaskFailed(persistedTaskId, e.message)
            }
        }

        return taskId
    }

    private fun updatePersistedTask(taskId: UUID?, status: DbTaskStatus, message: String?, current: Int?, total: Int?) {
        if (taskId == null || taskRepository == null) return
        try {
            taskRepository.updateProgress(taskId, status, message, current, total)
        } catch (e: Exception) {
            logger.warn("Failed to update persisted task {}: {}", taskId, e.message)
        }
    }

    private fun updatePersistedTaskCompleted(taskId: UUID?) {
        if (taskId == null || taskRepository == null) return
        try {
            taskRepository.markCompleted(taskId)
        } catch (e: Exception) {
            logger.warn("Failed to mark task {} as completed: {}", taskId, e.message)
        }
    }

    private fun updatePersistedTaskFailed(taskId: UUID?, errorMessage: String?) {
        if (taskId == null || taskRepository == null) return
        try {
            taskRepository.markFailed(taskId, errorMessage)
        } catch (e: Exception) {
            logger.warn("Failed to mark task {} as failed: {}", taskId, e.message)
        }
    }

    /**
     * Get progress for a task
     */
    suspend fun getProgress(taskId: String): TaskProgress? {
        return progressMutex.withLock {
            progressMap[taskId]
        }
    }

    /**
     * Get all task progress
     */
    suspend fun getAllProgress(): List<TaskProgress> {
        return progressMutex.withLock {
            progressMap.values.toList()
        }
    }

    private suspend fun updateProgress(progress: TaskProgress) {
        progressMutex.withLock {
            progressMap[progress.taskId] = progress
        }
    }

    suspend fun getRateLimiterStatus(): OrchestratorRateLimiterStatus {
        return rateLimiter.getStatus()
    }

    private fun findConnectorOrThrow(url: String): Connector {
        return connectorRegistry.findConnector(url)
            ?: throw IllegalArgumentException("No connector found for URL: $url")
    }

    private suspend fun <T> withRateLimit(connector: Connector, block: suspend () -> T): T {
        return rateLimiter.withRateLimit(connector, block)
    }

    /**
     * Extract series URL from chapter URL using the connector's implementation.
     */
    private fun extractSeriesUrl(connector: Connector, chapterUrl: String): String {
        return connector.extractSeriesUrl(chapterUrl)
    }

    fun shutdown() {
        scope.cancel()
    }
}
