package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.connector.Connector
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext

class ConnectorExtensionAdapter(private val connector: Connector) : Extension {
    override val id: String = connector.id
    override val name: String = connector.name
    override val version: String = "bundled"

    override fun capabilities() = setOf(Capability.CanFetchSeries, Capability.CanDownloadChapters)

    override fun onEnable(context: ExtensionContext) {
        context.connectorRegistry.register(connector)
    }

    override fun onDisable() {}
}
