package dev.koenv.chaptervault.orchestration.cache

import dev.koenv.chaptervault.core.config.CacheCleanupConfig
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Service for cleaning up stale cached series that are not in the user's library.
 * Runs periodically based on configuration settings.
 */
class CacheCleanupService(
    private val seriesRepository: SeriesRepositoryPort,
    private val config: CacheCleanupConfig
) {
    private val logger = LoggerFactory.getLogger(CacheCleanupService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cleanupJob: Job? = null

    /**
     * Start the scheduled cleanup job.
     */
    fun start() {
        if (!config.enabled) {
            logger.info("Cache cleanup is disabled")
            return
        }

        logger.info(
            "Starting cache cleanup service (TTL: {} days, interval: {} hours)",
            config.ttlDays,
            config.runIntervalHours
        )

        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    runCleanup()
                } catch (e: Exception) {
                    logger.error("Cache cleanup failed: {}", e.message, e)
                }

                delay(config.runIntervalHours * 60 * 60 * 1000L)
            }
        }
    }

    /**
     * Stop the scheduled cleanup job.
     */
    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
        logger.info("Cache cleanup service stopped")
    }

    /**
     * Run cleanup manually.
     * @return Number of series deleted
     */
    fun runCleanup(): Int {
        val cutoffTime = Instant.now().minus(config.ttlDays.toLong(), ChronoUnit.DAYS)
        logger.info("Running cache cleanup (removing series not updated since {})", cutoffTime)

        val deletedCount = seriesRepository.deleteStaleCache(cutoffTime)

        if (deletedCount > 0) {
            logger.info("Cache cleanup completed: deleted {} stale series", deletedCount)
        } else {
            logger.debug("Cache cleanup completed: no stale series to delete")
        }

        return deletedCount
    }

    /**
     * Get the count of stale series that would be cleaned up.
     */
    fun getStaleCount(): Int {
        val cutoffTime = Instant.now().minus(config.ttlDays.toLong(), ChronoUnit.DAYS)
        return seriesRepository.findStaleCache(cutoffTime, excludeLibrary = true).size
    }
}
