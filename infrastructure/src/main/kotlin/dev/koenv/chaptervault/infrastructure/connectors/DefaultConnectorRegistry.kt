package dev.koenv.chaptervault.infrastructure.connectors

import dev.koenv.chaptervault.kernel.connector.Connector
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import java.util.concurrent.ConcurrentHashMap

class DefaultConnectorRegistry : ConnectorRegistry {
    private val entries = ConcurrentHashMap<String, Connector>()

    override fun register(connector: Connector) { entries[connector.id] = connector }
    override fun unregister(id: String) { entries.remove(id) }
    override fun findById(id: String): Connector? = entries[id]
    override fun all(): List<Connector> = entries.values.toList()
}
