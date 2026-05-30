package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.extension.ConnectorRegistrar

fun ConnectorRegistrar.register(connector: Connector) = registerRaw(connector.id, connector)
