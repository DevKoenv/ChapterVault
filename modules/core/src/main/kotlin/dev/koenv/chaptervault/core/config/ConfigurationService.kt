package dev.koenv.chaptervault.core.config

import dev.koenv.chaptervault.core.BuildConfig
import dev.koenv.chaptervault.core.ratelimit.BucketConfig
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.ratelimit.SiteRateLimits
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration service interface for reading application configuration.
 *
 * Supports:
 * - YAML configuration files
 * - Environment variable overrides
 * - Connector-specific configuration sections
 * - Runtime reload (requires restart in some cases)
 */
interface ConfigurationService {

    /**
     * Get the root application configuration.
     */
    fun getAppConfig(): AppConfig

    /**
     * Get configuration for a specific connector by name.
     * Returns null if no specific configuration exists.
     */
    fun getConnectorConfig(connectorName: String): ConnectorSpecificConfig?

    /**
     * Get browser pool configuration.
     */
    fun getBrowserConfig(): BrowserPoolConfig

    /**
     * Get HTTP client configuration.
     */
    fun getHttpConfig(): HttpClientConfig

    /**
     * Reload configuration from disk.
     * Some changes may require application restart.
     */
    fun reload()

    /**
     * Get the configuration file path.
     */
    fun getConfigPath(): File

    /**
     * Check if configuration file exists.
     */
    fun configExists(): Boolean
}

/**
 * Root application configuration.
 */
data class AppConfig(
    val dataPath: String = "${System.getProperty("user.home")}/ChapterVault/data",
    val server: ServerConfig = ServerConfig(),
    val storage: StorageAppConfig = StorageAppConfig(),
    val database: DatabaseAppConfig = DatabaseAppConfig(),
    val browser: BrowserPoolConfig = BrowserPoolConfig(),
    val http: HttpClientConfig = HttpClientConfig(),
    val cache: CacheCleanupConfig = CacheCleanupConfig(),
    val connectors: Map<String, ConnectorSpecificConfig> = emptyMap()
)

/**
 * Server configuration.
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val baseUrl: String? = null  // If null, derived from host:port
)

/**
 * Storage configuration from config file.
 */
data class StorageAppConfig(
    val path: String = "${System.getProperty("user.home")}/ChapterVault/downloads",
    val format: String = "cbz",  // cbz or folder
    val minFreeSpaceMb: Long = 500
)

/**
 * Database configuration from config file.
 */
data class DatabaseAppConfig(
    val type: String = "sqlite",  // sqlite, h2, h2_memory, postgresql
    val path: String? = null,  // For file-based DBs (derived from dataPath if not set)
    val host: String = "localhost",  // For PostgreSQL
    val port: Int = 5432,  // For PostgreSQL
    val name: String = "chaptervault",  // For PostgreSQL
    val username: String? = null,
    val password: String? = null
)

/**
 * Browser pool configuration.
 */
data class BrowserPoolConfig(
    val enabled: Boolean = true,
    val maxBrowsers: Int = 2,
    val maxContextsPerBrowser: Int = 3,
    val browserIdleTimeoutSeconds: Long = 300,  // 5 minutes
    val contextIdleTimeoutSeconds: Long = 60,   // 1 minute
    val headless: Boolean = true,
    val browserType: String = "chromium",  // chromium, firefox, webkit
    val userAgent: String? = null,
    val viewportWidth: Int = 1920,
    val viewportHeight: Int = 1080,
    val locale: String = "en-US",
    val timezone: String? = null,
    val blockResources: List<String> = listOf("image", "media", "font"),  // For faster loading when not needed
    val extraArgs: List<String> = emptyList()
)

/**
 * HTTP client configuration.
 */
data class HttpClientConfig(
    val connectTimeoutSeconds: Long = 30,
    val readTimeoutSeconds: Long = 60,
    val maxRetries: Int = 3,
    val retryDelayMillis: Long = 1000,
    val userAgent: String = "ChapterVault/${BuildConfig.VERSION}",
    val followRedirects: Boolean = true,
    val maxRedirects: Int = 5,
    val proxy: ProxyConfig? = null
)

/**
 * Proxy configuration.
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val type: String = "http"  // http, socks4, socks5
)

/**
 * Connector-specific configuration.
 * Each connector can have its own section with custom settings.
 */
data class ConnectorSpecificConfig(
    val enabled: Boolean = true,
    val priority: Int? = null,  // Override default priority
    val auth: AuthConfig? = null,
    val rateLimit: RateLimitOverride? = null,
    val siteRateLimits: SiteRateLimitsOverride? = null,
    val custom: Map<String, Any> = emptyMap()  // Connector-specific settings
) {
    /**
     * Get a custom string value.
     */
    fun getString(key: String): String? = custom[key]?.toString()

    /**
     * Get a custom int value.
     */
    fun getInt(key: String): Int? = custom[key]?.toString()?.toIntOrNull()

    /**
     * Get a custom boolean value.
     */
    fun getBoolean(key: String): Boolean? = custom[key]?.toString()?.toBooleanStrictOrNull()

    /**
     * Get a custom list value.
     */
    @Suppress("UNCHECKED_CAST")
    fun getList(key: String): List<String>? = custom[key] as? List<String>
}

/**
 * Authentication configuration for a connector.
 */
data class AuthConfig(
    val username: String? = null,
    val password: String? = null,
    val apiKey: String? = null,
    val token: String? = null,
    val cookies: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap()
)

/**
 * Rate limit override for a connector.
 */
data class RateLimitOverride(
    val minDelayMillis: Long? = null,
    val maxConcurrent: Int? = null,
    val maxRequestsPerWindow: Int? = null,
    val windowDurationMillis: Long? = null
)

/**
 * Site-level rate limits override from YAML configuration.
 *
 * Supports overriding default limits and defining named bucket overrides.
 *
 * YAML format:
 * ```yaml
 * site_rate_limits:
 *   defaults:
 *     min_delay_millis: 500
 *     max_concurrent: 2
 *     max_requests_per_window: 60
 *     window_duration_millis: 60000
 *   buckets:
 *     cdn:
 *       unlimited: true
 *     api:
 *       min_delay_millis: 100
 *       max_concurrent: 4
 * ```
 */
data class SiteRateLimitsOverride(
    val defaultMinDelayMillis: Long? = null,
    val defaultMaxConcurrent: Int? = null,
    val defaultMaxRequestsPerWindow: Int? = null,
    val defaultWindowDurationMillis: Long? = null,
    val buckets: Map<String, BucketOverride> = emptyMap()
)

/**
 * Override for a single named rate limit bucket.
 */
data class BucketOverride(
    val unlimited: Boolean = false,
    val minDelayMillis: Long? = null,
    val maxConcurrent: Int? = null,
    val maxRequestsPerWindow: Int? = null,
    val windowDurationMillis: Long? = null
)

/**
 * Cache cleanup configuration.
 * Controls automatic cleanup of non-library series metadata.
 */
data class CacheCleanupConfig(
    /** Whether to enable automatic cache cleanup */
    val enabled: Boolean = true,
    /** Number of days before non-library series are cleaned up */
    val ttlDays: Int = 90,
    /** How often the cleanup job runs (in hours) */
    val runIntervalHours: Int = 24
)

/**
 * Apply this override onto [base], replacing only the fields that are explicitly set.
 */
fun RateLimitOverride.applyTo(base: RateLimitConfig): RateLimitConfig = base.copy(
    minDelay = minDelayMillis?.milliseconds ?: base.minDelay,
    maxConcurrent = maxConcurrent ?: base.maxConcurrent,
    maxRequestsPerWindow = maxRequestsPerWindow ?: base.maxRequestsPerWindow,
    windowDuration = windowDurationMillis?.milliseconds ?: base.windowDuration
)

/**
 * Apply this override onto [base], replacing only the fields that are explicitly set.
 * Named buckets in this override are merged into the base bucket map;
 * buckets present in [base] but absent from this override are kept unchanged.
 */
fun SiteRateLimitsOverride.applyTo(base: SiteRateLimits): SiteRateLimits {
    val mergedDefaults = base.defaultLimits.copy(
        minDelay = defaultMinDelayMillis?.milliseconds ?: base.defaultLimits.minDelay,
        maxConcurrent = defaultMaxConcurrent ?: base.defaultLimits.maxConcurrent,
        maxRequestsPerWindow = defaultMaxRequestsPerWindow ?: base.defaultLimits.maxRequestsPerWindow,
        windowDuration = defaultWindowDurationMillis?.milliseconds ?: base.defaultLimits.windowDuration
    )
    val mergedBuckets = base.buckets.toMutableMap()
    buckets.forEach { (name, override) ->
        mergedBuckets[name] = override.applyTo(mergedBuckets[name] ?: BucketConfig())
    }
    return SiteRateLimits(defaultLimits = mergedDefaults, buckets = mergedBuckets)
}

/**
 * Apply this override onto [base], replacing only the fields that are explicitly set.
 * If [unlimited] is true, returns an unlimited bucket regardless of [base].
 */
fun BucketOverride.applyTo(base: BucketConfig): BucketConfig {
    if (unlimited) return BucketConfig(limits = null)
    val baseLimits = base.limits ?: RateLimitConfig()
    return BucketConfig(
        limits = baseLimits.copy(
            minDelay = minDelayMillis?.milliseconds ?: baseLimits.minDelay,
            maxConcurrent = maxConcurrent ?: baseLimits.maxConcurrent,
            maxRequestsPerWindow = maxRequestsPerWindow ?: baseLimits.maxRequestsPerWindow,
            windowDuration = windowDurationMillis?.milliseconds ?: baseLimits.windowDuration
        )
    )
}
