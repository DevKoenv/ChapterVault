package dev.koenv.chaptervault.core.connector

/**
 * Registry for managing connectors.
 * Orchestrator uses this to find the appropriate connector for a URL.
 */
interface ConnectorRegistry {
    /**
     * Register a connector.
     * Rate limit config is now part of connector.config, not passed separately.
     */
    fun register(connector: Connector)

    /**
     * Find a connector that can handle the given URL
     */
    fun findConnector(url: String): Connector?

    /**
     * Find a connector by its unique ID.
     * This is the preferred method for lookups.
     */
    fun findById(id: String): Connector?

    /**
     * Find a connector by name (case-insensitive).
     * @deprecated Use [findById] instead for more reliable lookups.
     */
    fun findByName(name: String): Connector?

    /**
     * Get all registered connectors
     */
    fun getAllConnectors(): List<Connector>
}
