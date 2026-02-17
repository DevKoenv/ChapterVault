package dev.koenv.chaptervault.orchestration.ratelimit

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Point-in-time snapshot of a [DomainBucket]'s state.
 */
data class BucketSnapshot(
    val name: String,
    val maxConcurrent: Int,
    val minDelayMs: Long,
    val maxRequestsPerWindow: Int,
    val windowDurationMs: Long,
    val lastRequestTime: Long,
    val requestsInCurrentWindow: Int,
    val backoffUntil: Long,
    val isInBackoff: Boolean,
    val adaptiveDelayMultiplier: Double,
    val consecutive429Count: Int
)

/**
 * A single rate limit bucket with concurrency control, minimum delay,
 * sliding window rate limiting, and adaptive backoff from 429 responses.
 *
 * Each bucket corresponds to a host, named bucket tag, or domain group.
 * The semaphore is held for the duration of the actual HTTP request,
 * ensuring correct concurrency control.
 */
internal class DomainBucket(
    val name: String,
    private val baseConfig: RateLimitConfig
) {

    private val logger = LoggerFactory.getLogger(DomainBucket::class.java)

    private val concurrencySemaphore = Semaphore(baseConfig.maxConcurrent)
    private val stateMutex = Mutex()
    private var lastRequestTime: Long = 0L
    private val requestTimestamps = ArrayDeque<Long>()

    // Adaptive backoff state from 429 responses
    private var backoffUntil: Long = 0L
    private var adaptiveDelayMultiplier: Double = 1.0
    private var consecutive429Count: Int = 0

    /**
     * Execute a block while holding the rate limit permit.
     * The semaphore is held for the duration of [block], ensuring
     * correct concurrency control over the actual HTTP request.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        concurrencySemaphore.acquire()
        try {
            waitForBackoff()
            waitForMinDelay()
            waitForWindowSlot()
            if (baseConfig.minDelay.isPositive() || baseConfig.maxRequestsPerWindow > 0) {
                recordRequest()
            }
            return block()
        } finally {
            concurrencySemaphore.release()
        }
    }

    /**
     * Report a 429 response so the bucket increases backoff for all requests.
     *
     * Uses AIMD (Additive Increase / Multiplicative Decrease) style adjustment:
     * - On 429: multiply delay by 1.5, set exponential backoff cooldown
     * - On success: decrease delay by 0.1 (additive decrease back to baseline)
     *
     * @param retryAfterSeconds Parsed Retry-After header value, or null.
     */
    suspend fun report429(retryAfterSeconds: Long?) {
        stateMutex.withLock {
            consecutive429Count++
            val now = System.currentTimeMillis()

            if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                backoffUntil = now + (retryAfterSeconds * 1000)
                logger.info(
                    "Bucket [{}]: 429 received, backing off for {}s (Retry-After)",
                    name, retryAfterSeconds
                )
            } else {
                // Exponential backoff: 2s, 4s, 8s, 16s, 32s, capped at 60s
                val backoffMs = minOf(
                    60_000L,
                    2_000L * (1L shl (consecutive429Count - 1).coerceAtMost(5))
                )
                backoffUntil = now + backoffMs
                logger.info(
                    "Bucket [{}]: 429 received (#{} consecutive), backing off for {}ms",
                    name, consecutive429Count, backoffMs
                )
            }

            // Multiplicative increase of delay
            adaptiveDelayMultiplier = (adaptiveDelayMultiplier * 1.5).coerceAtMost(5.0)
        }
    }

    /**
     * Report a successful response, allowing adaptive backoff to recover.
     */
    suspend fun reportSuccess() {
        stateMutex.withLock {
            consecutive429Count = 0
            // Additive decrease back toward baseline
            adaptiveDelayMultiplier = (adaptiveDelayMultiplier - 0.1).coerceAtLeast(1.0)
        }
    }

    private suspend fun waitForBackoff() {
        val waitUntil = stateMutex.withLock { backoffUntil }
        val now = System.currentTimeMillis()
        if (waitUntil > now) {
            val waitMs = waitUntil - now
            logger.debug("Bucket [{}]: waiting {}ms for 429 backoff", name, waitMs)
            delay(waitMs.milliseconds)
        }
    }

    private suspend fun waitForMinDelay() {
        if (!baseConfig.minDelay.isPositive() && adaptiveDelayMultiplier <= 1.0) return

        val delayNeeded = stateMutex.withLock {
            val effectiveMinDelayMs = (baseConfig.minDelay.inWholeMilliseconds * adaptiveDelayMultiplier).toLong()
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastRequestTime
            maxOf(0L, effectiveMinDelayMs - timeSinceLast)
        }

        if (delayNeeded > 0) {
            logger.debug("Bucket [{}]: waiting {}ms for minDelay", name, delayNeeded)
            delay(delayNeeded.milliseconds)
        }
    }

    private suspend fun waitForWindowSlot() {
        if (baseConfig.maxRequestsPerWindow <= 0) return

        val maxRequests = baseConfig.maxRequestsPerWindow
        val windowDurationMs = baseConfig.windowDuration.inWholeMilliseconds

        while (true) {
            val waitTime = stateMutex.withLock {
                val now = System.currentTimeMillis()
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
                    // Add small buffer to avoid race conditions
                    maxOf(1L, waitNeeded + 10)
                }
            }

            if (waitTime == 0L) break

            logger.debug("Bucket [{}]: waiting {}ms for window slot", name, waitTime)
            delay(waitTime.milliseconds)
        }
    }

    private suspend fun recordRequest() {
        stateMutex.withLock {
            val now = System.currentTimeMillis()
            lastRequestTime = now
            requestTimestamps.addLast(now)
        }
    }

    internal suspend fun snapshot(): BucketSnapshot = stateMutex.withLock {
        val now = System.currentTimeMillis()
        val windowStart = now - baseConfig.windowDuration.inWholeMilliseconds
        val currentWindowCount = requestTimestamps.count { it >= windowStart }

        BucketSnapshot(
            name = name,
            maxConcurrent = baseConfig.maxConcurrent,
            minDelayMs = baseConfig.minDelay.inWholeMilliseconds,
            maxRequestsPerWindow = baseConfig.maxRequestsPerWindow,
            windowDurationMs = baseConfig.windowDuration.inWholeMilliseconds,
            lastRequestTime = lastRequestTime,
            requestsInCurrentWindow = currentWindowCount,
            backoffUntil = backoffUntil,
            isInBackoff = backoffUntil > now,
            adaptiveDelayMultiplier = adaptiveDelayMultiplier,
            consecutive429Count = consecutive429Count
        )
    }
}
