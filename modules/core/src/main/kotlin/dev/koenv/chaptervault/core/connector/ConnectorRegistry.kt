package dev.koenv.chaptervault.core.connector

/**
 * Registry for managing connectors.
 * Orchestrator uses this to find the appropriate connector for a URL.
 */
interface ConnectorRegistry {
    /**
     * Register a connector. Pass [addonId] when registering on behalf of an addon
     * so the connector can be unregistered when the addon is disabled.
     */
    fun register(connector: Connector, addonId: String? = null)

    /**
     * Find a connector that can handle the given URL.
     */
    fun findConnector(url: String): Connector?

    /**
     * Find a connector by its unique ID.
     */
    fun findById(id: String): Connector?

    /**
     * Get all registered connectors.
     */
    fun getAllConnectors(): List<Connector>

    /**
     * Unregister a connector by ID. Returns true if a connector was removed.
     */
    fun unregister(connectorId: String): Boolean

    /**
     * Unregister all connectors owned by the given addon. Returns the IDs of removed connectors.
     */
    fun unregisterByAddon(addonId: String): List<String>

    /**
     * Returns the addon ID that owns the given connector, or null for built-in connectors.
     */
    fun getAddonId(connectorId: String): String?
}
