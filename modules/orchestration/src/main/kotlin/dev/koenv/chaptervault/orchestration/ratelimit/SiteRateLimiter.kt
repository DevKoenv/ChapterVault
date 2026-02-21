package dev.koenv.chaptervault.orchestration.ratelimit

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.ratelimit.SiteRateLimits
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Snapshot of a [RateLimitConfig] for external consumption.
 */
data class RateLimitConfigSnapshot(
    val minDelayMs: Long,
    val maxConcurrent: Int,
    val maxRequestsPerWindow: Int,
    val windowDurationMs: Long
)

/**
 * Point-in-time status of the [SiteRateLimiter].
 */
data class SiteRateLimiterStatus(
    val registeredConnectors: List<String>,
    val namedBucketConfigs: Map<String, RateLimitConfigSnapshot?>,
    val activeBuckets: List<BucketSnapshot>
)

/**
 * Domain-aware site rate limiter that throttles individual outgoing HTTP requests.
 *
 * Resolves rate limit buckets at request time using two strategies:
 * 1. **Bucket tag**: If the instruction has a `rateLimitBucket` tag (e.g., "cdn", "api"),
 *    look up the named bucket from the connector's [SiteRateLimits] configuration.
 * 2. **Host auto-bucketing**: Otherwise, extract the host from the URL and create/reuse
 *    a per-host bucket with default limits.
 *
 * Named buckets with `null` limits bypass rate limiting entirely (useful for CDNs).
 * Bucket tags are prefixed with the connector name to avoid collisions between connectors.
 * Host-based buckets are shared globally across connectors (the target site doesn't care
 * which connector is calling it).
 *
 * The [withRateLimit] method holds the concurrency semaphore for the duration of the
 * actual HTTP request, ensuring correct concurrency control.
 *
 * Supports adaptive backoff: when a 429 response is received, [report429] increases
 * the delay for that bucket using AIMD (Additive Increase / Multiplicative Decrease).
 *
 * @param timeSource Injectable time provider for testability.
 */
class SiteRateLimiter(
    private val timeSource: () -> Long = System::currentTimeMillis
) {

    private val logger = LoggerFactory.getLogger(SiteRateLimiter::class.java)

    // Named bucket configs per connector: "connectorName:bucketTag" -> RateLimitConfig (limited)
    private val namedBucketLimits = ConcurrentHashMap<String, RateLimitConfig>()

    // Named bucket keys where rate limiting is bypassed entirely (unlimited)
    // Stored separately because ConcurrentHashMap does not permit null values
    private val unlimitedBuckets: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Per-connector default limits for auto-created host buckets
    private val connectorDefaultLimits = ConcurrentHashMap<String, RateLimitConfig>()

    // Fallback limits when connectorName is unknown
    private val fallbackLimits = RateLimitConfig()

    // Live bucket instances: bucketKey -> DomainBucket
    private val buckets = ConcurrentHashMap<String, DomainBucket>()

    // ========================================================================
    // Registration
    // ========================================================================

    /**
     * Register domain rate limit configuration from a connector.
     *
     * Named buckets are stored with a connector-prefixed key so different connectors
     * can define independent buckets with the same name (e.g., both can have "cdn").
     */
    fun registerConnector(connectorName: String, config: SiteRateLimits) {
        connectorDefaultLimits[connectorName] = config.defaultLimits

        config.buckets.forEach { (bucketName, bucketConfig) ->
            val key = "$connectorName:$bucketName"
            val limits = bucketConfig.limits  // local val required for smart cast across module boundary
            if (limits == null) {
                unlimitedBuckets.add(key)
                logger.debug("Registered named bucket [{}]: unlimited", key)
            } else {
                namedBucketLimits[key] = limits
                logger.debug("Registered named bucket [{}]: {}", key, limits)
            }
        }

        logger.debug(
            "Registered site rate limits for connector {}: {} named buckets, defaults={}",
            connectorName, config.buckets.size, config.defaultLimits
        )
    }

    // ========================================================================
    // Rate Limit Execution
    // ========================================================================

    /**
     * Execute a block while respecting the rate limit for the given URL and optional bucket tag.
     *
     * Resolution order:
     * 1. If [bucketTag] is set, look up the named bucket for the connector.
     *    If the bucket is unlimited (null limits), bypass rate limiting.
     *    If no named bucket config is found, fall through to host-based bucketing.
     * 2. Extract the host from [url] and use a per-host bucket with default limits.
     *
     * The concurrency semaphore is held for the duration of [block].
     *
     * @param url The request URL (used for host extraction if no bucket tag)
     * @param connectorName The connector making the request (used for bucket tag namespacing)
     * @param bucketTag Optional bucket tag from the instruction (e.g., "cdn", "api")
     * @param block The HTTP request to execute
     */
    suspend fun <T> withRateLimit(
        url: String,
        connectorName: String?,
        bucketTag: String?,
        block: suspend () -> T
    ): T {
        val bucket = resolveBucket(url, connectorName, bucketTag)
            ?: return block() // null = unlimited, bypass rate limiting

        return bucket.withPermit(block)
    }

    /**
     * Report a 429 response for adaptive backoff on the resolved bucket.
     *
     * @param url The URL that received the 429
     * @param connectorName The connector name
     * @param bucketTag Optional bucket tag
     * @param retryAfterSeconds Parsed Retry-After header value, null if absent
     */
    suspend fun report429(url: String, connectorName: String?, bucketTag: String?, retryAfterSeconds: Long?) {
        val bucket = resolveBucket(url, connectorName, bucketTag) ?: return
        bucket.report429(retryAfterSeconds)
    }

    /**
     * Report a successful response to allow adaptive backoff recovery.
     */
    suspend fun reportSuccess(url: String, connectorName: String?, bucketTag: String?) {
        val bucket = resolveBucket(url, connectorName, bucketTag) ?: return
        bucket.reportSuccess()
    }

    // ========================================================================
    // Bucket Eviction
    // ========================================================================

    /**
     * Remove buckets that have not been accessed within [maxIdleDuration].
     *
     * Call periodically to prevent unbounded growth of the bucket map when many
     * different hosts are accessed over time.
     *
     * @return Number of buckets evicted.
     */
    fun evictStaleBuckets(maxIdleDuration: Duration = 10.minutes): Int {
        val cutoff = timeSource() - maxIdleDuration.inWholeMilliseconds
        var evicted = 0

        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastAccessedAt < cutoff) {
                iterator.remove()
                evicted++
                logger.debug("Evicted stale bucket [{}]", entry.key)
            }
        }

        if (evicted > 0) {
            logger.info("Evicted {} stale rate limit buckets (idle > {})", evicted, maxIdleDuration)
        }
        return evicted
    }

    // ========================================================================
    // Bucket Resolution
    // ========================================================================

    /**
     * Resolve the rate limit bucket for a request.
     * Returns null if the request should bypass rate limiting (unlimited bucket).
     */
    private fun resolveBucket(url: String, connectorName: String?, bucketTag: String?): DomainBucket? {
        // 1. Check for named bucket tag
        if (bucketTag != null && connectorName != null) {
            val configKey = "$connectorName:$bucketTag"
            if (unlimitedBuckets.contains(configKey)) {
                return null // Unlimited bucket — bypass rate limiting
            }
            val limits = namedBucketLimits[configKey]
            if (limits != null) {
                return getOrCreateBucket("tag:$configKey", limits)
            }
            // Named bucket not found for this connector — fall through to host-based
            logger.debug(
                "Named bucket [{}] not found for connector {}, falling back to host-based",
                bucketTag, connectorName
            )
        }

        // 2. Host-based auto-bucketing
        val defaults = connectorName?.let { connectorDefaultLimits[it] } ?: fallbackLimits
        val host = extractHost(url)
        if (host == null) {
            logger.debug("Could not extract host from URL: {}", url)
            return getOrCreateBucket("unknown", defaults)
        }

        return getOrCreateBucket("host:$host", defaults)
    }

    private fun getOrCreateBucket(key: String, config: RateLimitConfig): DomainBucket {
        return buckets.computeIfAbsent(key) {
            logger.debug("Creating rate limit bucket [{}]: {}", key, config)
            DomainBucket(key, config, timeSource)
        }
    }

    suspend fun getStatus(): SiteRateLimiterStatus {
        val configs = buildMap<String, RateLimitConfigSnapshot?> {
            namedBucketLimits.forEach { (key, config) ->
                put(key, RateLimitConfigSnapshot(
                    minDelayMs = config.minDelay.inWholeMilliseconds,
                    maxConcurrent = config.maxConcurrent,
                    maxRequestsPerWindow = config.maxRequestsPerWindow,
                    windowDurationMs = config.windowDuration.inWholeMilliseconds
                ))
            }
            unlimitedBuckets.forEach { key -> put(key, null) }
        }

        val snapshots = buckets.values.map { it.snapshot() }

        return SiteRateLimiterStatus(
            registeredConnectors = connectorDefaultLimits.keys().toList(),
            namedBucketConfigs = configs,
            activeBuckets = snapshots
        )
    }

    private fun extractHost(url: String): String? {
        return try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            // Fallback: simple string parsing
            val withoutProtocol = url.removePrefix("https://").removePrefix("http://")
            val host = withoutProtocol.substringBefore("/").substringBefore(":").lowercase()
            host.ifEmpty { null }
        }
    }
}
