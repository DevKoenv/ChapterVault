package dev.koenv.chaptervault.orchestration.ratelimit

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Point-in-time snapshot of a [SlidingWindowLimiter]'s state.
 */
data class SlidingWindowSnapshot(
    val maxConcurrent: Int,
    val minDelayMs: Long,
    val maxRequestsPerWindow: Int,
    val windowDurationMs: Long,
    val lastRequestTime: Long,
    val requestsInCurrentWindow: Int
)

/**
 * Core rate limiter with concurrency control, minimum delay, and sliding window rate limiting.
 *
 * Provides:
 * - **Concurrency limiting** via semaphore (held for the duration of the request)
 * - **Minimum delay** between requests with optimistic reservation to prevent TOCTOU races
 *   when [RateLimitConfig.maxConcurrent] > 1
 * - **Sliding window** rate limiting using a log of request timestamps
 * - **Injectable time source** for deterministic testing
 *
 * This class is the shared foundation for both the connector-level [RateLimiter]
 * and the site-level [DomainBucket].
 *
 * @param name Human-readable name for logging (e.g., connector id or bucket key)
 * @param config Rate limit configuration
 * @param timeSource Time provider, defaults to [System.currentTimeMillis]
 */
internal class SlidingWindowLimiter(
    private val name: String,
    private val config: RateLimitConfig,
    private val timeSource: () -> Long = System::currentTimeMillis
) {

    private val logger = LoggerFactory.getLogger(SlidingWindowLimiter::class.java)

    private val concurrencySemaphore = Semaphore(config.maxConcurrent)
    private val stateMutex = Mutex()
    private var lastRequestTime: Long = 0L
    private val requestTimestamps = ArrayDeque<Long>()

    /**
     * Execute a block while respecting rate limits.
     * The semaphore is held for the duration of [block].
     *
     * @param effectiveMinDelayMs Override for the minimum delay in ms. When non-null, this
     *   value is used instead of [RateLimitConfig.minDelay], allowing callers to apply
     *   adaptive multipliers. Pass null to use the config's minDelay.
     * @param preAction Optional action to run after acquiring the semaphore but before
     *   enforcing delays (e.g., adaptive backoff wait). This runs while holding the
     *   concurrency permit.
     * @param block The operation to execute under rate limiting.
     */
    suspend fun <T> withPermit(
        effectiveMinDelayMs: Long? = null,
        preAction: (suspend () -> Unit)? = null,
        block: suspend () -> T
    ): T {
        concurrencySemaphore.acquire()
        try {
            preAction?.invoke()
            val minDelay = effectiveMinDelayMs ?: config.minDelay.inWholeMilliseconds
            waitForMinDelay(minDelay)
            waitForWindowSlot()
            recordRequest()
            return block()
        } finally {
            concurrencySemaphore.release()
        }
    }

    /**
     * Wait for minimum delay between requests.
     *
     * Updates [lastRequestTime] optimistically (to the projected fire time) inside the mutex
     * before delaying. This prevents a TOCTOU race where two concurrent coroutines read the
     * same [lastRequestTime] and both compute the same (too-short) delay.
     */
    private suspend fun waitForMinDelay(effectiveMinDelayMs: Long) {
        if (effectiveMinDelayMs <= 0) return

        val delayNeeded = stateMutex.withLock {
            val now = timeSource()
            val timeSinceLast = now - lastRequestTime
            val neededDelay = maxOf(0L, effectiveMinDelayMs - timeSinceLast)
            // Optimistic reservation: set lastRequestTime to the projected fire time
            // so the next concurrent waiter computes a delay *after* this one.
            lastRequestTime = now + neededDelay
            neededDelay
        }

        if (delayNeeded > 0) {
            logger.debug("[{}]: waiting {}ms for minDelay", name, delayNeeded)
            delay(delayNeeded.milliseconds)
        }
    }

    /**
     * Wait for a slot in the sliding window.
     * Returns immediately if [RateLimitConfig.maxRequestsPerWindow] is not positive.
     */
    private suspend fun waitForWindowSlot() {
        if (config.maxRequestsPerWindow <= 0) return

        val maxRequests = config.maxRequestsPerWindow
        val windowDurationMs = config.windowDuration.inWholeMilliseconds

        while (true) {
            val waitTime = stateMutex.withLock {
                val now = timeSource()
                val windowStart = now - windowDurationMs

                // Remove expired timestamps from the sliding window
                while (requestTimestamps.isNotEmpty() &&
                    requestTimestamps.first() < windowStart
                ) {
                    requestTimestamps.removeFirst()
                }

                if (requestTimestamps.size < maxRequests) {
                    0L
                } else {
                    val oldestTimestamp = requestTimestamps.first()
                    val expiresAt = oldestTimestamp + windowDurationMs
                    val waitNeeded = expiresAt - now
                    // Small buffer to avoid race conditions
                    maxOf(1L, waitNeeded + 10)
                }
            }

            if (waitTime == 0L) break

            logger.debug("[{}]: waiting {}ms for window slot", name, waitTime)
            delay(waitTime.milliseconds)
        }
    }

    /**
     * Record that a request is being made.
     * Updates [lastRequestTime] to the actual time (which may be later than the optimistic
     * reservation if [waitForWindowSlot] introduced additional delay).
     */
    private suspend fun recordRequest() {
        stateMutex.withLock {
            val now = timeSource()
            lastRequestTime = now
            if (config.maxRequestsPerWindow > 0) {
                requestTimestamps.addLast(now)
            }
        }
    }

    /**
     * Get a point-in-time snapshot of the rate limiter state.
     */
    suspend fun snapshot(): SlidingWindowSnapshot = stateMutex.withLock {
        val now = timeSource()
        val windowStart = now - config.windowDuration.inWholeMilliseconds
        val currentWindowCount = requestTimestamps.count { it >= windowStart }

        SlidingWindowSnapshot(
            maxConcurrent = config.maxConcurrent,
            minDelayMs = config.minDelay.inWholeMilliseconds,
            maxRequestsPerWindow = config.maxRequestsPerWindow,
            windowDurationMs = config.windowDuration.inWholeMilliseconds,
            lastRequestTime = lastRequestTime,
            requestsInCurrentWindow = currentWindowCount
        )
    }
}
