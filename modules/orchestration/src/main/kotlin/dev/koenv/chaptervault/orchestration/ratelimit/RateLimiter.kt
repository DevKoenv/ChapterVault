package dev.koenv.chaptervault.orchestration.ratelimit

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rate limiter that enforces (per feature, when configured):
 * - Minimum delay between requests (minDelay) — disabled when [Duration.ZERO]
 * - Maximum concurrent requests (maxConcurrent) — always active, defaults to 1 (serial)
 * - Maximum requests per time window (maxRequestsPerWindow/windowDuration) — disabled when 0
 *
 * Uses a sliding window log algorithm for accurate rate limiting.
 */
class RateLimiter {

    private val logger = LoggerFactory.getLogger(RateLimiter::class.java)

    // State per connector (keyed by connector name for consistency across instances)
    private val connectorStates = ConcurrentHashMap<String, ConnectorState>()

    /**
     * Execute a block with rate limiting applied.
     * This is the preferred API - wraps the entire operation with rate limit enforcement.
     */
    suspend fun <T> withRateLimit(connector: Connector, block: suspend () -> T): T {
        val config = connector.config.rateLimitConfig
        val state = getOrCreateState(connector)

        // 1. Acquire concurrency permit (blocks if at max concurrent)
        state.concurrencySemaphore.acquire()

        try {
            // 2. Wait for minDelay since last request
            waitForMinDelay(state, config)

            // 3. Wait for window slot if at rate limit
            waitForWindowSlot(state, config)

            // 4. Record this request timestamp (for minDelay and window tracking)
            if (config.minDelay.isPositive() || config.maxRequestsPerWindow > 0) {
                recordRequest(state)
            }

            // 5. Execute the actual request
            return block()
        } finally {
            // 6. Release concurrency permit
            state.concurrencySemaphore.release()
        }
    }

    /**
     * Get or create state for a connector.
     * Uses connector name as key for consistency.
     */
    private fun getOrCreateState(connector: Connector): ConnectorState {
        val key = connector.config.name
        return connectorStates.getOrPut(key) {
            ConnectorState(
                concurrencySemaphore = Semaphore(connector.config.rateLimitConfig.maxConcurrent)
            )
        }
    }

    /**
     * Wait for minDelay since the last request to this connector.
     * Returns immediately if minDelay is not positive.
     */
    private suspend fun waitForMinDelay(state: ConnectorState, config: RateLimitConfig) {
        if (!config.minDelay.isPositive()) return

        val delayNeeded = state.mutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - state.lastRequestTime
            val minDelayMs = config.minDelay.inWholeMilliseconds

            maxOf(0L, minDelayMs - timeSinceLast)
        }

        if (delayNeeded > 0) {
            logger.debug("Rate limiter: waiting {}ms for minDelay", delayNeeded)
            delay(delayNeeded.milliseconds)
        }
    }

    /**
     * Wait for a slot in the rate limit window.
     * Returns immediately if maxRequestsPerWindow is not positive.
     * Uses sliding window log algorithm.
     */
    private suspend fun waitForWindowSlot(state: ConnectorState, config: RateLimitConfig) {
        if (config.maxRequestsPerWindow <= 0) return

        val maxRequests = config.maxRequestsPerWindow
        val windowDurationMs = config.windowDuration.inWholeMilliseconds

        // Keep trying until we have a slot
        while (true) {
            val waitTime = state.mutex.withLock {
                val now = System.currentTimeMillis()
                val windowStart = now - windowDurationMs

                // Remove expired timestamps from the sliding window
                while (state.requestTimestamps.isNotEmpty() &&
                    state.requestTimestamps.first() < windowStart
                ) {
                    state.requestTimestamps.removeFirst()
                }

                // Check if we have room in the window
                if (state.requestTimestamps.size < maxRequests) {
                    // We have a slot, no need to wait
                    0L
                } else {
                    // Window is full, calculate wait time until oldest expires
                    val oldestTimestamp = state.requestTimestamps.first()
                    val expiresAt = oldestTimestamp + windowDurationMs
                    val waitNeeded = expiresAt - now

                    // Add small buffer to avoid race conditions
                    maxOf(1L, waitNeeded + 10)
                }
            }

            if (waitTime == 0L) {
                break // We have a slot
            }

            logger.debug("Rate limiter: waiting {}ms for window slot", waitTime)
            delay(waitTime.milliseconds)
        }
    }

    /**
     * Record that a request was made.
     */
    private suspend fun recordRequest(state: ConnectorState) {
        state.mutex.withLock {
            val now = System.currentTimeMillis()
            state.lastRequestTime = now
            state.requestTimestamps.addLast(now)
        }
    }

    /**
     * State tracked per connector.
     */
    private data class ConnectorState(
        val mutex: Mutex = Mutex(),
        var lastRequestTime: Long = 0L,
        val concurrencySemaphore: Semaphore,
        val requestTimestamps: ArrayDeque<Long> = ArrayDeque()
    )
}
