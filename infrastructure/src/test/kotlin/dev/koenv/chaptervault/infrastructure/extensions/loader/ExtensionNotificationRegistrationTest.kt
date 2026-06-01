package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.infrastructure.notifications.DefaultNotificationChannelRegistry
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.DefaultExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionConfig
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtensionNotificationRegistrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extension-registered channel is available after enable`() {
        val channelRegistry = DefaultNotificationChannelRegistry()
        val fixture = NotificationTestFixture.build(notificationRegistry = channelRegistry, tempDir = tempDir)
        val channel =
            object : NotificationChannel {
                override val typeId = "custom.test"

                override suspend fun send(
                    targetUrl: String,
                    targetToken: String?,
                    event: NotificationEvent,
                ) {}
            }
        fixture.registerChannelOnEnable(channel)
        fixture.service.loadAll()
        assertNotNull(channelRegistry.find("custom.test"))
    }

    @Test
    fun `extension-registered channel is removed after disable`() {
        val channelRegistry = DefaultNotificationChannelRegistry()
        val fixture = NotificationTestFixture.build(notificationRegistry = channelRegistry, tempDir = tempDir)
        val channel =
            object : NotificationChannel {
                override val typeId = "custom.test"

                override suspend fun send(
                    targetUrl: String,
                    targetToken: String?,
                    event: NotificationEvent,
                ) {}
            }
        fixture.registerChannelOnEnable(channel)
        fixture.service.loadAll()
        fixture.service.disable(fixture.bundledId)
        assertNull(channelRegistry.find("custom.test"))
    }
}

private class NotificationTestFixture private constructor(
    val service: ExtensionLoaderService,
    val bundledId: String,
    private val extension: ChannelRegisteringExtension,
) {
    fun registerChannelOnEnable(channel: NotificationChannel) {
        extension.channelToRegister = channel
    }

    companion object {
        fun build(
            notificationRegistry: NotificationChannelRegistry,
            tempDir: Path,
        ): NotificationTestFixture {
            val extensionId = "notification-test-ext"
            val ext = ChannelRegisteringExtension(extensionId)
            val connRegistry = simpleNotificationTestConnectorRegistry()
            val svc =
                ExtensionLoaderService(
                    extensionRegistry = DefaultExtensionRegistry(),
                    connectorRegistryDelegate = connRegistry,
                    enricherRegistryDelegate = noopNotificationTestEnricherRegistry(),
                    notificationRegistryDelegate = notificationRegistry,
                    contextFactory = { _, dir -> makeNotificationTestContext(connRegistry, notificationRegistry, dir) },
                    externalLoader = ExternalExtensionLoader(extensionsDir = tempDir, serverVersion = "1.0.0"),
                    bundledExtensions = listOf(ext),
                )
            return NotificationTestFixture(service = svc, bundledId = extensionId, extension = ext)
        }
    }
}

private class ChannelRegisteringExtension(
    override val id: String,
) : Extension {
    override val name = id
    override val version = "1.0.0"
    var channelToRegister: NotificationChannel? = null

    override fun capabilities(): Set<Capability> = emptySet()

    override fun onEnable(context: ExtensionContext) {
        channelToRegister?.let { context.notificationRegistry.register(it) }
    }

    override fun onDisable() {}
}

private fun simpleNotificationTestConnectorRegistry(): dev.koenv.chaptervault.kernel.connector.ConnectorRegistry {
    val entries = ConcurrentHashMap<String, dev.koenv.chaptervault.kernel.connector.Connector>()
    return object : dev.koenv.chaptervault.kernel.connector.ConnectorRegistry {
        override fun register(connector: dev.koenv.chaptervault.kernel.connector.Connector) {
            entries[connector.id] = connector
        }

        override fun unregister(id: String) {
            entries.remove(id)
        }

        override fun findById(id: String) = entries[id]

        override fun all() = entries.values.toList()
    }
}

private fun noopNotificationTestEnricherRegistry(): MetadataEnricherRegistry =
    object : MetadataEnricherRegistry {
        override fun register(
            enricher: MetadataEnricher,
            priority: Int,
        ) = Unit

        override fun unregister(id: String) = Unit

        override fun all(): List<MetadataEnricher> = emptyList()
    }

private fun makeNotificationTestContext(
    connRegistry: dev.koenv.chaptervault.kernel.connector.ConnectorRegistry,
    notificationRegistry: NotificationChannelRegistry,
    dataDir: Path,
): ExtensionContext =
    object : ExtensionContext {
        override val httpClient get() = error("not needed in test")
        override val library get() = error("not needed in test")
        override val progress get() = error("not needed in test")
        override val system get() = error("not needed in test")
        override val connectorRegistry = connRegistry
        override val dataDir = dataDir
        override val enricherRegistry get() = error("not needed in test")
        override val notificationRegistry = notificationRegistry
        override val config: ExtensionConfig =
            object : ExtensionConfig {
                override fun get(key: String): String? = null
            }

        override fun rateLimiter(
            bucket: String,
            requestsPerSecond: Double,
        ) = error("not needed in test")

        override fun logger(name: String) = org.slf4j.LoggerFactory.getLogger(name)
    }
