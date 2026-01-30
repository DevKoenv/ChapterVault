package dev.koenv.chaptervault.core.config

import java.io.File

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
    val server: ServerConfig = ServerConfig(),
    val storage: StorageAppConfig = StorageAppConfig(),
    val database: DatabaseAppConfig = DatabaseAppConfig(),
    val browser: BrowserPoolConfig = BrowserPoolConfig(),
    val http: HttpClientConfig = HttpClientConfig(),
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
    val path: String? = null,  // Default: ~/ChapterVault/downloads
    val format: String = "cbz",  // cbz or folder
    val minFreeSpaceMb: Long = 500
)

/**
 * Database configuration from config file.
 */
data class DatabaseAppConfig(
    val type: String = "h2",  // h2, sqlite, postgresql
    val path: String? = null,  // For file-based DBs
    val host: String? = null,  // For PostgreSQL
    val port: Int? = null,
    val name: String? = null,
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
    val userAgent: String = "ChapterVault/1.0",
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
    val maxRequestsPerMinute: Int? = null
)
