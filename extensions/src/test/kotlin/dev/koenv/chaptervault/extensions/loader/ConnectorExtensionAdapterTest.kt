package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.extensions.connectors.Connector
import dev.koenv.chaptervault.extensions.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectorExtensionAdapterTest {
    private fun fakeConnector(id: String): Connector =
        mockk(relaxed = true) {
            every { this@mockk.id } returns id
            every { this@mockk.name } returns id
        }

    @Test
    fun `id and name match connector id`() {
        val connector = fakeConnector("mangadex")
        val adapter = ConnectorExtensionAdapter(connector)
        assertEquals("mangadex", adapter.id)
        assertEquals("mangadex", adapter.name)
    }

    @Test
    fun `capabilities include CanFetchSeries and CanDownloadChapters`() {
        val adapter = ConnectorExtensionAdapter(fakeConnector("test"))
        assertTrue(Capability.CanFetchSeries in adapter.capabilities())
        assertTrue(Capability.CanDownloadChapters in adapter.capabilities())
    }

    @Test
    fun `onEnable registers the connector`() {
        val connector = fakeConnector("mangadex")
        val adapter = ConnectorExtensionAdapter(connector)
        val registry = DefaultConnectorRegistry()
        val trackingRegistry = TrackingConnectorRegistry(registry)
        val context: ExtensionContext =
            mockk(relaxed = true) {
                every { connectorRegistry } returns trackingRegistry
            }
        adapter.onEnable(context)
        assertNotNull(registry.findById("mangadex"))
        assertTrue("mangadex" in trackingRegistry.registeredIds)
    }

    @Test
    fun `onDisable is a no-op`() {
        val adapter = ConnectorExtensionAdapter(fakeConnector("test"))
        adapter.onDisable()
    }

    @Test
    fun `registeredIds does not change on unregister`() {
        val connectorRegistry = DefaultConnectorRegistry()
        val trackingRegistry = TrackingConnectorRegistry(connectorRegistry)
        trackingRegistry.registerRaw("mangadex", fakeConnector("mangadex"))
        trackingRegistry.unregister("mangadex")
        assertTrue("mangadex" in trackingRegistry.registeredIds)
    }
}
