package dev.koenv.chaptervault.core.ratelimit

/**
 * Configuration for domain-aware site-level rate limiting.
 *
 * Connectors declare named buckets with specific rate limits. At request time,
 * the limiter resolves which bucket to use:
 * 1. If the instruction has a [rateLimitBucket][dev.koenv.chaptervault.core.execution.FetchHtml.rateLimitBucket]
 *    tag, look up that named bucket (prefixed with the connector name).
 * 2. Otherwise, extract the host from the URL and use a per-host bucket with [defaultLimits].
 *
 * Named buckets with `null` limits bypass rate limiting entirely (useful for CDNs).
 */
data class SiteRateLimits(
    /**
     * Default rate limits applied to auto-created per-host buckets.
     * Used when no explicit bucket tag is set on the instruction.
     */
    val defaultLimits: RateLimitConfig = RateLimitConfig(),

    /**
     * Named bucket configurations.
     *
     * Connectors tag individual instructions with a bucket name to route them
     * to a specific bucket instead of the default host-based bucket.
     *
     * Example buckets: "cdn" (unlimited), "api" (high throughput), "search" (conservative).
     */
    val buckets: Map<String, BucketConfig> = emptyMap()
)

/**
 * Configuration for a single named rate limit bucket.
 */
data class BucketConfig(
    /**
     * Rate limits for this bucket.
     *
     * Null means the bucket is bypassed — requests execute with no semaphore, no delay,
     * and no window tracking. Produced by [dev.koenv.chaptervault.core.ratelimit.BucketBuilder.unlimited]
     * when called with no subsequent property overrides.
     */
    val limits: RateLimitConfig? = RateLimitConfig()
)
