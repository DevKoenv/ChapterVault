package dev.koenv.chaptervault.extensions.connectors

import java.util.concurrent.ConcurrentHashMap

class DefaultConnectorRegistry : ConnectorRegistry {
    private val entries = ConcurrentHashMap<String, Connector>()

    override fun register(connector: Connector) {
        entries[connector.id] = connector
    }

    override fun findById(id: String): Connector? = entries[id]

    override fun all(): List<Connector> = entries.values.toList()
}
