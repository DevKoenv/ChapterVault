package dev.koenv.chaptervault.core.connector

/**
 * Registry for managing connectors.
 * Orchestrator uses this to find the appropriate connector for a URL.
 */
interface ConnectorRegistry {
    /**
     * Register a connector.
     */
    fun register(connector: Connector)

    /**
     * Find a connector that can handle the given URL
     */
    fun findConnector(url: String): Connector?

    /**
     * Find a connector by its unique ID.
     */
    fun findById(id: String): Connector?

    /**
     * Get all registered connectors
     */
    fun getAllConnectors(): List<Connector>
}
