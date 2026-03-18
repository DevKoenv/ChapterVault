package dev.koenv.chaptervault.orchestration.config

import dev.koenv.chaptervault.core.BuildConfig
import dev.koenv.chaptervault.core.config.*
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

/**
 * YAML-based configuration service implementation.
 *
 * Configuration file location (in order of precedence):
 * 1. CHAPTERVAULT_CONFIG environment variable
 * 2. ./config/chaptervault.yaml (relative to working directory)
 * 3. ~/.chaptervault/config.yaml (user home)
 * 4. /etc/chaptervault/config.yaml (system-wide, Linux only)
 *
 * Environment variables can override config values:
 * - CHAPTERVAULT_PORT -> server.port
 * - CHAPTERVAULT_HOST -> server.host
 * - CHAPTERVAULT_BASE_URL -> server.baseUrl
 * - CHAPTERVAULT_STORAGE_PATH -> storage.path
 * - CHAPTERVAULT_DB_TYPE -> database.type
 * - CHAPTERVAULT_DB_PATH -> database.path
 */
class ConfigurationServiceImpl(
    private val configDir: File? = null
) : ConfigurationService {

    private val logger = LoggerFactory.getLogger(ConfigurationServiceImpl::class.java)
    private val yaml = Yaml()

    private var configFile: File? = null
    private var appConfig: AppConfig = AppConfig()

    init {
        locateConfigFile()
        reload()
    }

    /**
     * Locate the configuration file.
     */
    private fun locateConfigFile() {
        val candidates = listOfNotNull(
            // Environment variable
            System.getenv("CHAPTERVAULT_CONFIG")?.let { File(it) },
            // Custom config dir
            configDir?.let { File(it, "chaptervault.yaml") },
            // Working directory
            File("config/chaptervault.yaml"),
            File("chaptervault.yaml"),
            // User home
            File(System.getProperty("user.home"), ".chaptervault/config.yaml"),
            // System-wide (Linux)
            File("/etc/chaptervault/config.yaml")
        )

        configFile = candidates.firstOrNull { it.exists() && it.canRead() }

        if (configFile != null) {
            logger.info("Using configuration file: ${configFile!!.absolutePath}")
        } else {
            logger.info("No configuration file found, using defaults. Searched: ${candidates.map { it.absolutePath }}")
        }
    }

    override fun reload() {
        val file = configFile
        if (file != null && file.exists()) {
            try {
                val rawConfig = FileInputStream(file).use { input ->
                    yaml.load<Map<String, Any>>(input) ?: emptyMap()
                }
                appConfig = parseConfig(rawConfig)
                logger.info("Configuration loaded from: ${file.absolutePath}")
            } catch (e: Exception) {
                logger.error("Failed to load configuration from ${file.absolutePath}: ${e.message}", e)
                appConfig = AppConfig()
            }
        } else {
            appConfig = AppConfig()
        }

        // Apply environment variable overrides
        appConfig = applyEnvironmentOverrides(appConfig)
    }

    override fun getAppConfig(): AppConfig = appConfig

    override fun getConnectorConfig(connectorName: String): ConnectorSpecificConfig? {
        // Try exact match first, then case-insensitive
        return appConfig.connectors[connectorName]
            ?: appConfig.connectors.entries.firstOrNull {
                it.key.equals(connectorName, ignoreCase = true)
            }?.value
    }

    override fun getBrowserConfig(): BrowserPoolConfig = appConfig.browser

    override fun getHttpConfig(): HttpClientConfig = appConfig.http

    override fun getConfigPath(): File {
        return configFile ?: File(
            System.getProperty("user.home"),
            ".chaptervault/config.yaml"
        )
    }

    override fun configExists(): Boolean = configFile?.exists() == true

    /**
     * Parse raw YAML map into typed configuration.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(raw: Map<String, Any>): AppConfig {
        val defaults = AppConfig()
        val dataPath = raw["dataPath"]?.toString()
            ?: raw["data_path"]?.toString()
            ?: defaults.dataPath
        val addonsPath = raw["addonsPath"]?.toString()
            ?: raw["addons_path"]?.toString()
            ?: defaults.addonsPath
        val server = (raw["server"] as? Map<String, Any>)?.let { parseServer(it) } ?: ServerConfig()
        val storage = (raw["storage"] as? Map<String, Any>)?.let { parseStorage(it) } ?: StorageAppConfig()
        val database = (raw["database"] as? Map<String, Any>)?.let { parseDatabase(it) } ?: DatabaseAppConfig()
        val browser = (raw["browser"] as? Map<String, Any>)?.let { parseBrowser(it) } ?: BrowserPoolConfig()
        val http = (raw["http"] as? Map<String, Any>)?.let { parseHttp(it) } ?: HttpClientConfig()
        val cache = (raw["cache"] as? Map<String, Any>)?.let { parseCache(it) } ?: CacheCleanupConfig()
        val connectors = (raw["connectors"] as? Map<String, Any>)?.let { parseConnectors(it) } ?: emptyMap()

        return AppConfig(
            dataPath = dataPath,
            addonsPath = addonsPath,
            server = server,
            storage = storage,
            database = database,
            browser = browser,
            http = http,
            cache = cache,
            connectors = connectors
        )
    }

    private fun parseServer(raw: Map<String, Any>): ServerConfig {
        val defaults = ServerConfig()
        return ServerConfig(
            host = raw["host"]?.toString() ?: defaults.host,
            port = (raw["port"] as? Number)?.toInt() ?: defaults.port,
            baseUrl = raw["baseUrl"]?.toString() ?: raw["base_url"]?.toString()
        )
    }

    private fun parseStorage(raw: Map<String, Any>): StorageAppConfig {
        val defaults = StorageAppConfig()
        return StorageAppConfig(
            path = raw["path"]?.toString() ?: defaults.path,
            format = raw["format"]?.toString() ?: defaults.format,
            minFreeSpaceMb = (raw["minFreeSpaceMb"] as? Number)?.toLong()
                ?: (raw["min_free_space_mb"] as? Number)?.toLong()
                ?: defaults.minFreeSpaceMb
        )
    }

    private fun parseDatabase(raw: Map<String, Any>): DatabaseAppConfig {
        val defaults = DatabaseAppConfig()
        return DatabaseAppConfig(
            type = raw["type"]?.toString() ?: defaults.type,
            path = raw["path"]?.toString(),
            host = raw["host"]?.toString() ?: defaults.host,
            port = (raw["port"] as? Number)?.toInt() ?: defaults.port,
            name = raw["name"]?.toString() ?: defaults.name,
            username = raw["username"]?.toString(),
            password = raw["password"]?.toString()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBrowser(raw: Map<String, Any>): BrowserPoolConfig {
        return BrowserPoolConfig(
            enabled = raw["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true,
            maxBrowsers = (raw["maxBrowsers"] as? Number)?.toInt()
                ?: (raw["max_browsers"] as? Number)?.toInt()
                ?: 2,
            maxContextsPerBrowser = (raw["maxContextsPerBrowser"] as? Number)?.toInt()
                ?: (raw["max_contexts_per_browser"] as? Number)?.toInt()
                ?: 3,
            browserIdleTimeoutSeconds = (raw["browserIdleTimeoutSeconds"] as? Number)?.toLong()
                ?: (raw["browser_idle_timeout_seconds"] as? Number)?.toLong()
                ?: 300,
            contextIdleTimeoutSeconds = (raw["contextIdleTimeoutSeconds"] as? Number)?.toLong()
                ?: (raw["context_idle_timeout_seconds"] as? Number)?.toLong()
                ?: 60,
            headless = raw["headless"]?.toString()?.toBooleanStrictOrNull() ?: true,
            browserType = raw["browserType"]?.toString()
                ?: raw["browser_type"]?.toString()
                ?: "chromium",
            userAgent = raw["userAgent"]?.toString() ?: raw["user_agent"]?.toString(),
            viewportWidth = (raw["viewportWidth"] as? Number)?.toInt()
                ?: (raw["viewport_width"] as? Number)?.toInt()
                ?: 1920,
            viewportHeight = (raw["viewportHeight"] as? Number)?.toInt()
                ?: (raw["viewport_height"] as? Number)?.toInt()
                ?: 1080,
            locale = raw["locale"]?.toString() ?: "en-US",
            timezone = raw["timezone"]?.toString(),
            blockResources = (raw["blockResources"] as? List<*>)?.mapNotNull { it?.toString() }
                ?: (raw["block_resources"] as? List<*>)?.mapNotNull { it?.toString() }
                ?: listOf("image", "media", "font"),
            extraArgs = (raw["extraArgs"] as? List<*>)?.mapNotNull { it?.toString() }
                ?: (raw["extra_args"] as? List<*>)?.mapNotNull { it?.toString() }
                ?: emptyList()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseHttp(raw: Map<String, Any>): HttpClientConfig {
        return HttpClientConfig(
            connectTimeoutSeconds = (raw["connectTimeoutSeconds"] as? Number)?.toLong()
                ?: (raw["connect_timeout_seconds"] as? Number)?.toLong()
                ?: 30,
            readTimeoutSeconds = (raw["readTimeoutSeconds"] as? Number)?.toLong()
                ?: (raw["read_timeout_seconds"] as? Number)?.toLong()
                ?: 60,
            maxRetries = (raw["maxRetries"] as? Number)?.toInt()
                ?: (raw["max_retries"] as? Number)?.toInt()
                ?: 3,
            retryDelayMillis = (raw["retryDelayMillis"] as? Number)?.toLong()
                ?: (raw["retry_delay_millis"] as? Number)?.toLong()
                ?: 1000,
            userAgent = raw["userAgent"]?.toString()
                ?: raw["user_agent"]?.toString()
                ?: "ChapterVault/${BuildConfig.VERSION}",
            followRedirects = raw["followRedirects"]?.toString()?.toBooleanStrictOrNull()
                ?: raw["follow_redirects"]?.toString()?.toBooleanStrictOrNull()
                ?: true,
            maxRedirects = (raw["maxRedirects"] as? Number)?.toInt()
                ?: (raw["max_redirects"] as? Number)?.toInt()
                ?: 5,
            proxy = (raw["proxy"] as? Map<String, Any>)?.let { parseProxy(it) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCache(raw: Map<String, Any>): CacheCleanupConfig {
        val cleanup = (raw["cleanup"] as? Map<String, Any>) ?: raw
        return CacheCleanupConfig(
            enabled = cleanup["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true,
            ttlDays = (cleanup["ttlDays"] as? Number)?.toInt()
                ?: (cleanup["ttl_days"] as? Number)?.toInt()
                ?: 90,
            runIntervalHours = (cleanup["runIntervalHours"] as? Number)?.toInt()
                ?: (cleanup["run_interval_hours"] as? Number)?.toInt()
                ?: 24
        )
    }

    private fun parseProxy(raw: Map<String, Any>): ProxyConfig {
        return ProxyConfig(
            host = raw["host"]?.toString() ?: "localhost",
            port = (raw["port"] as? Number)?.toInt() ?: 8080,
            username = raw["username"]?.toString(),
            password = raw["password"]?.toString(),
            type = raw["type"]?.toString() ?: "http"
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConnectors(raw: Map<String, Any>): Map<String, ConnectorSpecificConfig> {
        return raw.mapNotNull { (name, value) ->
            val connectorRaw = value as? Map<String, Any> ?: return@mapNotNull null
            name to parseConnectorConfig(connectorRaw)
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConnectorConfig(raw: Map<String, Any>): ConnectorSpecificConfig {
        val auth = (raw["auth"] as? Map<String, Any>)?.let { parseAuth(it) }
        val rateLimit = (raw["rateLimit"] as? Map<String, Any>)?.let { parseRateLimit(it) }
            ?: (raw["rate_limit"] as? Map<String, Any>)?.let { parseRateLimit(it) }
        val siteRateLimits = (raw["siteRateLimits"] as? Map<String, Any>)?.let { parseSiteRateLimits(it) }
            ?: (raw["site_rate_limits"] as? Map<String, Any>)?.let { parseSiteRateLimits(it) }

        // Everything else goes to custom
        val reserved = setOf(
            "enabled", "priority", "auth",
            "rateLimit", "rate_limit",
            "siteRateLimits", "site_rate_limits"
        )
        val custom = raw.filterKeys { it !in reserved }

        return ConnectorSpecificConfig(
            enabled = raw["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true,
            priority = (raw["priority"] as? Number)?.toInt(),
            auth = auth,
            rateLimit = rateLimit,
            siteRateLimits = siteRateLimits,
            custom = custom
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAuth(raw: Map<String, Any>): AuthConfig {
        return AuthConfig(
            username = raw["username"]?.toString(),
            password = raw["password"]?.toString(),
            apiKey = raw["apiKey"]?.toString() ?: raw["api_key"]?.toString(),
            token = raw["token"]?.toString(),
            cookies = (raw["cookies"] as? Map<String, Any>)?.mapValues { it.value.toString() } ?: emptyMap(),
            headers = (raw["headers"] as? Map<String, Any>)?.mapValues { it.value.toString() } ?: emptyMap()
        )
    }

    private fun parseRateLimit(raw: Map<String, Any>): RateLimitOverride {
        return RateLimitOverride(
            minDelayMillis = (raw["minDelayMillis"] as? Number)?.toLong()
                ?: (raw["min_delay_millis"] as? Number)?.toLong(),
            maxConcurrent = (raw["maxConcurrent"] as? Number)?.toInt()
                ?: (raw["max_concurrent"] as? Number)?.toInt(),
            maxRequestsPerWindow = (raw["maxRequestsPerWindow"] as? Number)?.toInt()
                ?: (raw["max_requests_per_window"] as? Number)?.toInt()
                ?: (raw["max_requests_per_minute"] as? Number)?.toInt(),
            windowDurationMillis = (raw["windowDurationMillis"] as? Number)?.toLong()
                ?: (raw["window_duration_millis"] as? Number)?.toLong()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSiteRateLimits(raw: Map<String, Any>): SiteRateLimitsOverride {
        val defaults = (raw["defaults"] as? Map<String, Any>)
        val bucketsRaw = (raw["buckets"] as? Map<String, Any>) ?: emptyMap()

        val buckets = bucketsRaw.mapNotNull { (name, value) ->
            val bucketRaw = value as? Map<String, Any> ?: return@mapNotNull null
            name to parseBucketOverride(bucketRaw)
        }.toMap()

        return SiteRateLimitsOverride(
            defaultMinDelayMillis = defaults?.let {
                (it["minDelayMillis"] as? Number)?.toLong()
                    ?: (it["min_delay_millis"] as? Number)?.toLong()
            },
            defaultMaxConcurrent = defaults?.let {
                (it["maxConcurrent"] as? Number)?.toInt()
                    ?: (it["max_concurrent"] as? Number)?.toInt()
            },
            defaultMaxRequestsPerWindow = defaults?.let {
                (it["maxRequestsPerWindow"] as? Number)?.toInt()
                    ?: (it["max_requests_per_window"] as? Number)?.toInt()
                    ?: (it["max_requests_per_minute"] as? Number)?.toInt()
            },
            defaultWindowDurationMillis = defaults?.let {
                (it["windowDurationMillis"] as? Number)?.toLong()
                    ?: (it["window_duration_millis"] as? Number)?.toLong()
            },
            buckets = buckets
        )
    }

    private fun parseBucketOverride(raw: Map<String, Any>): BucketOverride {
        return BucketOverride(
            unlimited = raw["unlimited"]?.toString()?.toBooleanStrictOrNull() ?: false,
            minDelayMillis = (raw["minDelayMillis"] as? Number)?.toLong()
                ?: (raw["min_delay_millis"] as? Number)?.toLong(),
            maxConcurrent = (raw["maxConcurrent"] as? Number)?.toInt()
                ?: (raw["max_concurrent"] as? Number)?.toInt(),
            maxRequestsPerWindow = (raw["maxRequestsPerWindow"] as? Number)?.toInt()
                ?: (raw["max_requests_per_window"] as? Number)?.toInt()
                ?: (raw["max_requests_per_minute"] as? Number)?.toInt(),
            windowDurationMillis = (raw["windowDurationMillis"] as? Number)?.toLong()
                ?: (raw["window_duration_millis"] as? Number)?.toLong()
        )
    }

    /**
     * Apply environment variable overrides.
     */
    private fun applyEnvironmentOverrides(config: AppConfig): AppConfig {
        var result = config

        // Data path override
        System.getenv("CHAPTERVAULT_DATA_PATH")?.let {
            result = result.copy(dataPath = it)
        }

        // Addons path override
        System.getenv("CHAPTERVAULT_ADDONS_PATH")?.let {
            result = result.copy(addonsPath = it)
        }

        // Server overrides
        System.getenv("CHAPTERVAULT_PORT")?.toIntOrNull()?.let {
            result = result.copy(server = result.server.copy(port = it))
        }
        System.getenv("CHAPTERVAULT_HOST")?.let {
            result = result.copy(server = result.server.copy(host = it))
        }
        System.getenv("CHAPTERVAULT_BASE_URL")?.let {
            result = result.copy(server = result.server.copy(baseUrl = it))
        }

        // Storage overrides
        System.getenv("CHAPTERVAULT_STORAGE_PATH")?.let {
            result = result.copy(storage = result.storage.copy(path = it))
        }
        System.getenv("CHAPTERVAULT_STORAGE_FORMAT")?.let {
            result = result.copy(storage = result.storage.copy(format = it))
        }
        System.getenv("CHAPTERVAULT_MIN_FREE_SPACE_MB")?.toLongOrNull()?.let {
            result = result.copy(storage = result.storage.copy(minFreeSpaceMb = it))
        }

        // Database overrides
        System.getenv("CHAPTERVAULT_DB_TYPE")?.let {
            result = result.copy(database = result.database.copy(type = it))
        }
        System.getenv("CHAPTERVAULT_DB_PATH")?.let {
            result = result.copy(database = result.database.copy(path = it))
        }
        System.getenv("CHAPTERVAULT_DB_HOST")?.let {
            result = result.copy(database = result.database.copy(host = it))
        }
        System.getenv("CHAPTERVAULT_DB_PORT")?.toIntOrNull()?.let {
            result = result.copy(database = result.database.copy(port = it))
        }
        System.getenv("CHAPTERVAULT_DB_NAME")?.let {
            result = result.copy(database = result.database.copy(name = it))
        }
        System.getenv("CHAPTERVAULT_DB_USERNAME")?.let {
            result = result.copy(database = result.database.copy(username = it))
        }
        System.getenv("CHAPTERVAULT_DB_PASSWORD")?.let {
            result = result.copy(database = result.database.copy(password = it))
        }

        // Browser overrides
        System.getenv("CHAPTERVAULT_BROWSER_ENABLED")?.toBooleanStrictOrNull()?.let {
            result = result.copy(browser = result.browser.copy(enabled = it))
        }
        System.getenv("CHAPTERVAULT_BROWSER_MAX")?.toIntOrNull()?.let {
            result = result.copy(browser = result.browser.copy(maxBrowsers = it))
        }
        System.getenv("CHAPTERVAULT_BROWSER_HEADLESS")?.toBooleanStrictOrNull()?.let {
            result = result.copy(browser = result.browser.copy(headless = it))
        }

        return result
    }

    companion object {
        /**
         * Create a default configuration service.
         */
        fun create(): ConfigurationServiceImpl = ConfigurationServiceImpl()

        /**
         * Create a configuration service with a specific config directory.
         */
        fun create(configDir: File): ConfigurationServiceImpl = ConfigurationServiceImpl(configDir)
    }
}
