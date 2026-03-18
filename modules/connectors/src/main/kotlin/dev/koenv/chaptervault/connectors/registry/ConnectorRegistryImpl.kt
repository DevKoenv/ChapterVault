package dev.koenv.chaptervault.connectors.registry

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of ConnectorRegistry.
 * Manages connector registration and lookup with priority-based ordering.
 */
class ConnectorRegistryImpl : ConnectorRegistry {

    private val connectors = mutableListOf<Connector>()
    private val ownership = mutableMapOf<String, String>() // connectorId -> addonId

    /**
     * Register a connector. Rejects duplicate IDs with a log error.
     * Connectors are sorted by priority (descending) after registration.
     */
    override fun register(connector: Connector, addonId: String?) {
        val id = connector.config.id
        if (connectors.any { it.config.id == id }) {
            logger.error { "Connector with id '$id' is already registered — skipping duplicate" }
            return
        }
        connectors.add(connector)
        if (addonId != null) {
            ownership[id] = addonId
        }
        connectors.sortByDescending { it.config.priority }
    }

    /**
     * Find the best connector for a URL.
     * Returns the highest-priority connector that can handle the URL.
     */
    override fun findConnector(url: String): Connector? {
        return connectors.firstOrNull { it.canHandle(url) }
    }

    /**
     * Find all connectors that can handle a URL, sorted by priority.
     */
    fun findAllConnectors(url: String): List<Connector> {
        return connectors.filter { it.canHandle(url) }
    }

    /**
     * Find a connector by its unique ID.
     */
    override fun findById(id: String): Connector? {
        return connectors.find { it.config.id.equals(id, ignoreCase = true) }
    }

    override fun getAllConnectors(): List<Connector> {
        return connectors.toList()
    }

    override fun unregister(connectorId: String): Boolean {
        val removed = connectors.removeIf { it.config.id == connectorId }
        if (removed) ownership.remove(connectorId)
        return removed
    }

    override fun unregisterByAddon(addonId: String): List<String> {
        val ownedIds = ownership.entries.filter { it.value == addonId }.map { it.key }
        ownedIds.forEach { unregister(it) }
        return ownedIds
    }

    override fun getAddonId(connectorId: String): String? {
        return ownership[connectorId]
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
