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
 *     bucket("cdn") { unlimited() }
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
    private var limits: RateLimitConfig? = RateLimitConfig()
    private var isUnlimited = false

    var minDelay: Duration
        get() = limits?.minDelay ?: 1.seconds
        set(value) { ensureLimits(); limits = limits!!.copy(minDelay = value) }

    var maxConcurrent: Int
        get() = limits?.maxConcurrent ?: 1
        set(value) { ensureLimits(); limits = limits!!.copy(maxConcurrent = value) }

    var maxRequestsPerWindow: Int
        get() = limits?.maxRequestsPerWindow ?: 60
        set(value) { ensureLimits(); limits = limits!!.copy(maxRequestsPerWindow = value) }

    var windowDuration: Duration
        get() = limits?.windowDuration ?: 60.seconds
        set(value) { ensureLimits(); limits = limits!!.copy(windowDuration = value) }

    /**
     * Mark this bucket as unlimited — requests bypass rate limiting entirely.
     */
    fun unlimited() {
        isUnlimited = true
        limits = null
    }

    private fun ensureLimits() {
        if (limits == null) {
            limits = RateLimitConfig()
        }
        isUnlimited = false
    }

    fun build(): BucketConfig = BucketConfig(limits = if (isUnlimited) null else limits)
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
