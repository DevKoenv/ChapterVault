package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.extension.ConnectorRegistrar
import java.util.concurrent.ConcurrentHashMap

class DefaultConnectorRegistry :
    ConnectorRegistry,
    ConnectorRegistrar {
    private val entries = ConcurrentHashMap<String, Connector>()

    override fun register(connector: Connector) {
        entries[connector.id] = connector
    }

    override fun registerRaw(
        id: String,
        connector: Any,
    ) {
        register(connector as Connector)
    }

    override fun unregister(id: String) {
        entries.remove(id)
    }

    override fun findById(id: String): Connector? = entries[id]

    override fun all(): List<Connector> = entries.values.toList()
}
