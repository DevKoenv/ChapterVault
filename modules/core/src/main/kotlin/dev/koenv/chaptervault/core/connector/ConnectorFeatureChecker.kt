package dev.koenv.chaptervault.core.connector

/**
 * Exception thrown when a connector doesn't support a requested feature.
 */
class UnsupportedConnectorFeatureException(
    connectorName: String,
    feature: String
) : UnsupportedOperationException(
    "Connector '$connectorName' does not support feature: $feature"
)

/**
 * Extension functions for checking connector feature support.
 * Use these before calling connector methods to ensure the connector supports the operation.
 */

/**
 * Throws if the connector doesn't support search.
 */
fun Connector.requireSearch() {
    if (!config.features.supportsSearch) {
        throw UnsupportedConnectorFeatureException(config.name, "search")
    }
}

/**
 * Throws if the connector doesn't support batch download.
 */
fun Connector.requireBatchDownload() {
    if (!config.features.supportsBatchDownload) {
        throw UnsupportedConnectorFeatureException(config.name, "batch download")
    }
}

/**
 * Throws if the connector requires authentication but user is not authenticated.
 */
fun Connector.requireAuth(isAuthenticated: Boolean) {
    if (config.features.requiresAuth && !isAuthenticated) {
        throw UnsupportedConnectorFeatureException(config.name, "authentication required")
    }
}

/**
 * Returns true if the connector supports search.
 */
fun Connector.supportsSearch(): Boolean = config.features.supportsSearch

/**
 * Returns true if the connector supports batch download.
 */
fun Connector.supportsBatchDownload(): Boolean = config.features.supportsBatchDownload

/**
 * Returns true if the connector requires authentication.
 */
fun Connector.requiresAuth(): Boolean = config.features.requiresAuth

/**
 * Returns true if the connector can provide page counts.
 */
fun Connector.supportsPageCount(): Boolean = config.features.supportsPageCount

/**
 * Returns the maximum concurrent downloads allowed for this connector, or null if unlimited.
 */
fun Connector.maxConcurrentDownloads(): Int? = config.features.maxConcurrentDownloads
