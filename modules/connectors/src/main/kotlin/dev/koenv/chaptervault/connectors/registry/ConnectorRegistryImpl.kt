package dev.koenv.chaptervault.connectors.registry

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorRegistry

/**
 * Default implementation of ConnectorRegistry.
 * Manages connector registration and lookup with priority-based ordering.
 */
class ConnectorRegistryImpl : ConnectorRegistry {

    private val connectors = mutableListOf<Connector>()

    /**
     * Register a connector. Connectors are sorted by priority (descending).
     */
    override fun register(connector: Connector) {
        connectors.add(connector)
        // Keep sorted by priority (descending) for efficient lookup
        connectors.sortByDescending { it.config.priority }
    }

    /**
     * Find the best connector for a URL.
     * Returns the highest-priority connector that can handle the URL.
     */
    override fun findConnector(url: String): Connector? {
        // Already sorted by priority, so first match is highest priority
        return connectors.firstOrNull { it.canHandle(url) }
    }

    /**
     * Find all connectors that can handle a URL, sorted by priority.
     */
    fun findAllConnectors(url: String): List<Connector> {
        return connectors.filter { it.canHandle(url) }
    }

    /**
     * Find a connector by name.
     */
    override fun findByName(name: String): Connector? {
        return connectors.find { it.config.name.equals(name, ignoreCase = true) }
    }

    override fun getAllConnectors(): List<Connector> {
        return connectors.toList()
    }

    /**
     * Get a detailed error message when no connector is found.
     */
    fun getNoConnectorFoundMessage(url: String): String {
        return buildString {
            appendLine("No connector found for URL: $url")
            appendLine()
            appendLine("Registered connectors:")
            connectors.forEach { connector ->
                appendLine("  - ${connector.config.name} (priority: ${connector.config.priority})")
                connector.baseUrls.forEach { pattern ->
                    appendLine("      Pattern: $pattern")
                }
            }
            appendLine()
            appendLine("Make sure the URL matches one of the supported patterns.")
        }
    }
}
