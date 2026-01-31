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
 * Rate limiter that enforces:
 * - Minimum delay between requests (minDelay)
 * - Maximum concurrent requests (maxConcurrent)
 * - Maximum requests per time window (maxRequestsPerWindow/windowDuration)
 *
 * Uses a sliding window log algorithm for accurate rate limiting.
 */
class RateLimiter {

    private val logger = LoggerFactory.getLogger(RateLimiter::class.java)

    // State per connector (keyed by connector name for consistency across instances)
    private val connectorStates = ConcurrentHashMap<String, ConnectorState>()
    private val stateMutex = Mutex()

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

            // 4. Record this request timestamp
            recordRequest(state)

            // 5. Execute the actual request
            return block()
        } finally {
            // 6. Release concurrency permit
            state.concurrencySemaphore.release()
        }
    }

    /**
     * Legacy API: Acquire permission to make a request.
     * Prefer using withRateLimit() instead.
     */
    @Deprecated(
        "Use withRateLimit() which wraps the entire operation",
        ReplaceWith("withRateLimit(connector) { /* your request code */ }"),
        DeprecationLevel.WARNING
    )
    suspend fun acquire(connector: Connector, config: RateLimitConfig) {
        val state = getOrCreateState(connector)

        // Acquire concurrency permit
        state.concurrencySemaphore.acquire()

        try {
            // Wait for minDelay
            waitForMinDelay(state, config)

            // Wait for window slot
            waitForWindowSlot(state, config)

            // Record request
            recordRequest(state)
        } finally {
            // Note: For legacy API, we release immediately after acquiring
            // The caller is responsible for the actual request timing
            state.concurrencySemaphore.release()
        }
    }

    /**
     * Get or create state for a connector.
     * Uses connector name as key for consistency.
     */
    private suspend fun getOrCreateState(connector: Connector): ConnectorState {
        val key = connector.config.name
        return connectorStates.getOrPut(key) {
            ConnectorState(
                concurrencySemaphore = Semaphore(connector.config.rateLimitConfig.maxConcurrent)
            )
        }
    }

    /**
     * Wait for minDelay since the last request to this connector.
     */
    private suspend fun waitForMinDelay(state: ConnectorState, config: RateLimitConfig) {
        val delayNeeded = stateMutex.withLock {
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
     * Uses sliding window log algorithm.
     */
    private suspend fun waitForWindowSlot(state: ConnectorState, config: RateLimitConfig) {
        val maxRequests = config.maxRequestsPerWindow
        val windowDurationMs = config.windowDuration.inWholeMilliseconds

        // Keep trying until we have a slot
        while (true) {
            val waitTime = stateMutex.withLock {
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
        stateMutex.withLock {
            val now = System.currentTimeMillis()
            state.lastRequestTime = now
            state.requestTimestamps.addLast(now)
        }
    }

    /**
     * State tracked per connector.
     */
    private data class ConnectorState(
        // For minDelay enforcement
        var lastRequestTime: Long = 0L,

        // For maxConcurrent enforcement
        val concurrencySemaphore: Semaphore,

        // For sliding window rate limiting
        val requestTimestamps: ArrayDeque<Long> = ArrayDeque()
    )
}
