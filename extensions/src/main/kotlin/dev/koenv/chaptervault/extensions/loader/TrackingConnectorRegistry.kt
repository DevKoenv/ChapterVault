package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.extensions.connectors.Connector
import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.ConnectorRegistrar

class TrackingConnectorRegistry(
    private val delegate: ConnectorRegistry,
) : ConnectorRegistrar {
    private val _registeredIds = mutableListOf<String>()
    val registeredIds: List<String> get() = _registeredIds.toList()

    override fun registerRaw(id: String, connector: Any) {
        delegate.register(connector as Connector)
        _registeredIds.add(id)
    }

    override fun unregister(id: String) {
        delegate.unregister(id)
    }
}
