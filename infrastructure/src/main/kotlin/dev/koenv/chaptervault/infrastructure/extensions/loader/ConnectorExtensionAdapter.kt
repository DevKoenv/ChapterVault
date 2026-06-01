package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.kernel.connector.Connector
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext

/**
 * Wraps a [Connector] as an [Extension] so it can participate in the extension lifecycle.
 * Intended for use by external extension JARs that expose a connector.
 * Built-in connectors are registered directly to [dev.koenv.chaptervault.kernel.connector.ConnectorRegistry].
 */
class ConnectorExtensionAdapter(
    private val connector: Connector,
) : Extension {
    override val id: String = connector.id
    override val name: String = connector.name
    override val version: String = "bundled"

    override fun capabilities() = setOf(Capability.CanFetchSeries, Capability.CanDownloadChapters)

    override fun onEnable(context: ExtensionContext) {
        context.connectorRegistry.register(connector)
    }

    override fun onDisable() {}
}
