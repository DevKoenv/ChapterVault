package dev.koenv.chaptervault.core.connector

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig

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
     * Get all registered connectors
     */
    fun getAllConnectors(): List<Connector>
}
