package dev.koenv.chaptervault.core.ratelimit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Rate limit configuration for a connector/site.
 * Orchestrator uses this to enforce rate limits.
 */
data class RateLimitConfig(
    /**
     * Minimum delay between requests to the same site.
     * Null means no minimum delay is enforced.
     */
    val minDelay: Duration = Duration.ZERO,

    /**
     * Maximum number of concurrent requests to the same site.
     * Defaults to 1 (serial execution). Higher values allow more concurrency.
     */
    val maxConcurrent: Int = 1,

    /**
     * Maximum number of requests per time window.
     * 0 means no window-based rate limiting is enforced.
     */
    val maxRequestsPerWindow: Int = 0,

    /**
     * Time window for rate limiting.
     * Only used when [maxRequestsPerWindow] > 0.
     */
    val windowDuration: Duration = 60.seconds
) {
    init {
        require(maxConcurrent >= 1) { "maxConcurrent must be at least 1, got $maxConcurrent" }
    }
}
