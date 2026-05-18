package dev.koenv.chaptervault.extensions.connectors

interface ConnectorRegistry {
    fun register(connector: Connector, context: ConnectorContext? = null)
    fun findById(id: String): Connector?
    fun getContext(id: String): ConnectorContext?
    fun all(): List<Connector>
}
