package dev.koenv.chaptervault.orchestration.engine

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.storage.StorageSink
import dev.koenv.chaptervault.orchestration.ratelimit.RateLimiter
import dev.koenv.chaptervault.orchestration.task.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
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
 * 
 * Orchestrator NEVER fetches or parses data directly.
 */
class Orchestrator(
    private val connectorRegistry: ConnectorRegistry,
    private val storageSink: StorageSink,
    private val rateLimiter: RateLimiter = RateLimiter()
) {
    
    private val logger = LoggerFactory.getLogger(Orchestrator::class.java)
    private val progressMap = mutableMapOf<String, TaskProgress>()
    private val progressMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Search for series
     */
    suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        val taskId = Uuid.random().toString()
        logger.info("Starting search for: {}", query)
        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Searching for '$query'"))
        
        return try {
            val results = mutableListOf<SeriesSearchResult>()
            
            // Try all connectors that support search
            connectorRegistry.getAllConnectors().forEach { connector ->
                try {
                    val connectorResults = withRateLimit(connector) {
                        connector.searchSeries(query)
                    }
                    results.addAll(connectorResults)
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
                val seriesUrl = extractSeriesUrl(chapterUrl)
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
     */
    suspend fun downloadSeries(seriesUrl: String): String {
        val taskId = Uuid.random().toString()
        updateProgress(TaskProgress(taskId, TaskStatus.RUNNING, "Starting series download"))
        
        scope.launch {
            try {
                val connector = findConnectorOrThrow(seriesUrl)
                
                // Fetch series metadata
                val seriesMetadata = withRateLimit(connector) {
                    connector.fetchSeriesMetadata(seriesUrl)
                }
                
                // Fetch chapter list
                val chapters = withRateLimit(connector) {
                    connector.fetchChapterList(seriesUrl)
                }
                
                updateProgress(TaskProgress(
                    taskId, 
                    TaskStatus.RUNNING, 
                    "Downloading ${chapters.size} chapters", 
                    0, 
                    chapters.size
                ))
                
                // Begin series
                storageSink.beginSeries(seriesMetadata)
                
                // Download each chapter
                chapters.forEachIndexed { index, chapterMetadata ->
                    updateProgress(TaskProgress(
                        taskId,
                        TaskStatus.RUNNING,
                        "Downloading chapter ${index + 1}/${chapters.size}",
                        index + 1,
                        chapters.size
                    ))
                    
                    storageSink.beginChapter(chapterMetadata)
                    
                    withRateLimit(connector) {
                        connector.downloadChapter(chapterMetadata.url, storageSink)
                    }
                    
                    storageSink.endChapter()
                }
                
                // End series
                storageSink.endSeries()
                
                updateProgress(TaskProgress(
                    taskId,
                    TaskStatus.COMPLETED,
                    "Series downloaded successfully",
                    chapters.size,
                    chapters.size
                ))
            } catch (e: Exception) {
                updateProgress(TaskProgress(taskId, TaskStatus.FAILED, error = e.message))
            }
        }
        
        return taskId
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
    
    private fun findConnectorOrThrow(url: String): Connector {
        return connectorRegistry.findConnector(url)
            ?: throw IllegalArgumentException("No connector found for URL: $url")
    }
    
    private suspend fun <T> withRateLimit(connector: Connector, block: suspend () -> T): T {
        rateLimiter.acquire(connector, connector.config.rateLimitConfig)
        return block()
    }
    
    /**
     * Extract series URL from chapter URL
     * In a real implementation, this would be connector-specific
     */
    private fun extractSeriesUrl(chapterUrl: String): String {
        // Simple heuristic: remove last path segment
        return chapterUrl.substringBeforeLast("/")
    }
    
    fun shutdown() {
        scope.cancel()
    }
}
