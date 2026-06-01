package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.kernel.connector.Connector
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry

class TrackingConnectorRegistry(
    private val delegate: ConnectorRegistry,
) : ConnectorRegistry by delegate {
    private val _registeredIds = mutableListOf<String>()
    val registeredIds: List<String> get() = _registeredIds.toList()

    override fun register(connector: Connector) {
        delegate.register(connector)
        _registeredIds.add(connector.id)
    }
}
