package dev.koenv.chaptervault.core.ratelimit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DSL entry point for building a [SiteRateLimits] configuration.
 *
 * Example usage:
 * ```kotlin
 * siteRateLimits {
 *     defaults {
 *         maxConcurrent = 2
 *         minDelay = 500.milliseconds
 *     }
 *     // Bypass rate limiting entirely for CDN assets
 *     bucket("cdn") { unlimited() }
 *     // Unlimited baseline but cap parallel connections
 *     bucket("images") { unlimited(); maxConcurrent = 8 }
 *     bucket("api") {
 *         maxConcurrent = 4
 *         minDelay = 100.milliseconds
 *         maxRequestsPerWindow = 120
 *     }
 * }
 * ```
 */
fun siteRateLimits(block: SiteRateLimitsBuilder.() -> Unit): SiteRateLimits {
    val builder = SiteRateLimitsBuilder()
    builder.block()
    return builder.build()
}

@DslMarker
annotation class SiteRateLimitsDsl

@SiteRateLimitsDsl
class SiteRateLimitsBuilder {
    private var defaultLimits = RateLimitConfig()
    private val buckets = mutableMapOf<String, BucketConfig>()

    /**
     * Set default rate limits for auto-created per-host buckets.
     */
    fun defaults(block: RateLimitBuilder.() -> Unit) {
        val builder = RateLimitBuilder()
        builder.block()
        defaultLimits = builder.build()
    }

    /**
     * Define a named bucket with specific rate limits.
     *
     * Connectors tag individual instructions with this bucket name via
     * `rateLimitBucket = "name"` to route them here instead of the default
     * host-based bucket.
     */
    fun bucket(name: String, block: BucketBuilder.() -> Unit) {
        val builder = BucketBuilder()
        builder.block()
        buckets[name] = builder.build()
    }

    fun build(): SiteRateLimits = SiteRateLimits(defaultLimits, buckets.toMap())
}

@SiteRateLimitsDsl
class BucketBuilder {
    private var limits: RateLimitConfig = RateLimitConfig()
    private var isUnlimited = false

    var minDelay: Duration
        get() = limits.minDelay
        set(value) { limits = ensureUnlimitedBase().copy(minDelay = value) }

    var maxConcurrent: Int
        get() = limits.maxConcurrent
        set(value) { limits = ensureUnlimitedBase().copy(maxConcurrent = value) }

    var maxRequestsPerWindow: Int
        get() = limits.maxRequestsPerWindow
        set(value) { limits = ensureUnlimitedBase().copy(maxRequestsPerWindow = value) }

    var windowDuration: Duration
        get() = limits.windowDuration
        set(value) { limits = ensureUnlimitedBase().copy(windowDuration = value) }

    /**
     * Remove all rate limits from this bucket.
     *
     * Called alone, the bucket is bypassed entirely — requests execute with no semaphore,
     * no delay, and no window tracking (zero overhead).
     *
     * Any property set **after** this call layers a specific constraint on top of the
     * fully-permissive baseline, leaving all other dimensions unrestricted:
     * ```kotlin
     * bucket("images") { unlimited(); maxConcurrent = 8 }  // concurrency-only cap
     * bucket("cdn")    { unlimited() }                      // true bypass, zero overhead
     * ```
     */
    fun unlimited() {
        isUnlimited = true
        limits = UNLIMITED_BASE
    }

    // When a field is set after unlimited(), keep the permissive base so only the
    // explicitly overridden dimension is constrained.
    private fun ensureUnlimitedBase(): RateLimitConfig =
        if (isUnlimited) limits else limits.also { isUnlimited = false }

    fun build(): BucketConfig {
        // unlimited() with no subsequent overrides → bypass the rate limiter entirely
        if (isUnlimited && limits == UNLIMITED_BASE) return BucketConfig(limits = null)
        return BucketConfig(limits = limits)
    }

    companion object {
        val UNLIMITED_BASE = RateLimitConfig(
            minDelay = Duration.ZERO,
            maxConcurrent = Int.MAX_VALUE,
            maxRequestsPerWindow = 0,
            windowDuration = 60.seconds
        )
    }
}

@SiteRateLimitsDsl
class RateLimitBuilder {
    var minDelay: Duration = Duration.ZERO
    var maxConcurrent: Int = 1
    var maxRequestsPerWindow: Int = 0
    var windowDuration: Duration = 60.seconds

    fun build(): RateLimitConfig = RateLimitConfig(
        minDelay = minDelay,
        maxConcurrent = maxConcurrent,
        maxRequestsPerWindow = maxRequestsPerWindow,
        windowDuration = windowDuration
    )
}
