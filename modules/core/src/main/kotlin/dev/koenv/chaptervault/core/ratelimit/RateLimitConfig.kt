package dev.koenv.chaptervault.core.ratelimit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Rate limit configuration for a connector/site.
 * Orchestrator uses this to enforce rate limits.
 */
data class RateLimitConfig(
    /**
     * Minimum delay between requests to the same site
     */
    val minDelay: Duration = 1.seconds,
    
    /**
     * Maximum number of concurrent requests to the same site
     */
    val maxConcurrent: Int = 1,
    
    /**
     * Maximum number of requests per time window
     */
    val maxRequestsPerWindow: Int = 60,
    
    /**
     * Time window for rate limiting
     */
    val windowDuration: Duration = 60.seconds
)
