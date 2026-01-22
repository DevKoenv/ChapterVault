package dev.koenv.chaptervault.core.connector

/**
 * Feature flags for connector capabilities.
 * Useful for UI to know what operations are available.
 */
data class ConnectorFeatures(
    /**
     * Whether this connector supports search functionality
     */
    val supportsSearch: Boolean = true,
    
    /**
     * Whether this connector requires authentication
     */
    val requiresAuth: Boolean = false,
    
    /**
     * Whether this connector supports batch downloads
     */
    val supportsBatchDownload: Boolean = true,
    
    /**
     * Whether this connector can fetch chapter page count before downloading
     */
    val supportsPageCount: Boolean = true,
    
    /**
     * Maximum concurrent downloads supported (null = unlimited)
     */
    val maxConcurrentDownloads: Int? = 1
)
