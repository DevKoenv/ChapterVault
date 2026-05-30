package dev.koenv.chaptervault.extensions.connectors

interface ConnectorRegistry {
    fun register(connector: Connector)

    fun unregister(id: String)

    fun findById(id: String): Connector?

    fun all(): List<Connector>
}
