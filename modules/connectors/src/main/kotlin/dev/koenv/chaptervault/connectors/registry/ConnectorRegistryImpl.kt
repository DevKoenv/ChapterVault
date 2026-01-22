package dev.koenv.chaptervault.connectors.registry

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorRegistry

/**
 * Default implementation of ConnectorRegistry.
 * Manages connector registration and lookup.
 */
class ConnectorRegistryImpl : ConnectorRegistry {
    
    private val connectors = mutableListOf<Connector>()
    
    override fun register(connector: Connector) {
        connectors.add(connector)
    }
    
    override fun findConnector(url: String): Connector? {
        return connectors.firstOrNull { it.canHandle(url) }
    }
    
    override fun getAllConnectors(): List<Connector> {
        return connectors.toList()
    }
}
