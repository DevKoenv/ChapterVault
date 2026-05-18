package dev.koenv.chaptervault.extensions.connectors

import java.util.concurrent.ConcurrentHashMap

class DefaultConnectorRegistry : ConnectorRegistry {
    private val entries = ConcurrentHashMap<String, Pair<Connector, ConnectorContext?>>()

    override fun register(connector: Connector, context: ConnectorContext?) {
        entries[connector.id] = connector to context
    }

    override fun findById(id: String): Connector? = entries[id]?.first

    override fun getContext(id: String): ConnectorContext? = entries[id]?.second

    override fun all(): List<Connector> = entries.values.map { it.first }
}
