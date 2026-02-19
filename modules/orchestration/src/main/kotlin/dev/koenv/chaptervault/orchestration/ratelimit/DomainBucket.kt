package dev.koenv.chaptervault.orchestration.ratelimit

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
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
 * A site-level rate limit bucket with adaptive backoff from 429 responses.
 *
 * Delegates concurrency control, minimum delay, and sliding window enforcement to
 * [SlidingWindowLimiter], and adds adaptive backoff (AIMD) on top.
 *
 * Each bucket corresponds to a host, named bucket tag, or domain group.
 *
 * @param name Human-readable bucket name for logging
 * @param baseConfig Rate limit configuration
 * @param timeSource Injectable time provider for testability
 */
internal class DomainBucket(
    val name: String,
    private val baseConfig: RateLimitConfig,
    private val timeSource: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** Floor delay (ms) applied when adaptiveDelayMultiplier is elevated but baseConfig.minDelay is zero. */
        private const val ADAPTIVE_FLOOR_DELAY_MS = 100L
    }

    private val logger = LoggerFactory.getLogger(DomainBucket::class.java)

    private val limiter = SlidingWindowLimiter(name, baseConfig, timeSource)

    // Adaptive backoff state (protected by its own mutex, separate from the limiter's state)
    private val backoffMutex = Mutex()
    private var backoffUntil: Long = 0L
    private var adaptiveDelayMultiplier: Double = 1.0
    private var consecutive429Count: Int = 0

    /** Timestamp of last access, used for stale bucket eviction. */
    @Volatile
    var lastAccessedAt: Long = timeSource()
        private set

    /**
     * Execute a block while holding the rate limit permit.
     * The semaphore is held for the duration of [block], ensuring
     * correct concurrency control over the actual HTTP request.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        lastAccessedAt = timeSource()
        return limiter.withPermit(
            effectiveMinDelayMs = computeEffectiveMinDelay(),
            preAction = { waitForBackoff() }
        ) {
            block()
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
        backoffMutex.withLock {
            consecutive429Count++
            val now = timeSource()

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
        backoffMutex.withLock {
            consecutive429Count = 0
            // Additive decrease back toward baseline
            adaptiveDelayMultiplier = (adaptiveDelayMultiplier - 0.1).coerceAtLeast(1.0)
        }
    }

    /**
     * Compute the effective minimum delay in ms, applying the adaptive multiplier.
     * Returns null when no delay is needed (base delay is zero and multiplier is at baseline).
     */
    private suspend fun computeEffectiveMinDelay(): Long? {
        val multiplier = backoffMutex.withLock { adaptiveDelayMultiplier }
        if (!baseConfig.minDelay.isPositive() && multiplier <= 1.0) return null

        val baseDelayMs = if (baseConfig.minDelay.isPositive()) {
            baseConfig.minDelay.inWholeMilliseconds
        } else {
            ADAPTIVE_FLOOR_DELAY_MS
        }
        return (baseDelayMs * multiplier).toLong()
    }

    private suspend fun waitForBackoff() {
        val waitUntil = backoffMutex.withLock { backoffUntil }
        val now = timeSource()
        if (waitUntil > now) {
            val waitMs = waitUntil - now
            logger.debug("Bucket [{}]: waiting {}ms for 429 backoff", name, waitMs)
            delay(waitMs.milliseconds)
        }
    }

    internal suspend fun snapshot(): BucketSnapshot {
        val windowSnapshot = limiter.snapshot()
        return backoffMutex.withLock {
            val now = timeSource()
            BucketSnapshot(
                name = name,
                maxConcurrent = windowSnapshot.maxConcurrent,
                minDelayMs = windowSnapshot.minDelayMs,
                maxRequestsPerWindow = windowSnapshot.maxRequestsPerWindow,
                windowDurationMs = windowSnapshot.windowDurationMs,
                lastRequestTime = windowSnapshot.lastRequestTime,
                requestsInCurrentWindow = windowSnapshot.requestsInCurrentWindow,
                backoffUntil = backoffUntil,
                isInBackoff = backoffUntil > now,
                adaptiveDelayMultiplier = adaptiveDelayMultiplier,
                consecutive429Count = consecutive429Count
            )
        }
    }
}
