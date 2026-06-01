package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.kernel.connector.ChapterMetadata
import dev.koenv.chaptervault.kernel.connector.Connector
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.connector.DownloadResult
import dev.koenv.chaptervault.kernel.connector.SeriesMetadata
import dev.koenv.chaptervault.kernel.connector.SeriesSearchResult
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.DefaultExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionSource
import dev.koenv.chaptervault.kernel.extension.ExtensionStatus
import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtensionLoaderServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private fun noopEnricherRegistry(): MetadataEnricherRegistry =
        object : MetadataEnricherRegistry {
            override fun register(
                enricher: MetadataEnricher,
                priority: Int,
            ) = Unit

            override fun unregister(id: String) = Unit

            override fun all(): List<MetadataEnricher> = emptyList()
        }

    private fun noopNotificationRegistry(): NotificationChannelRegistry =
        object : NotificationChannelRegistry {
            override fun register(channel: NotificationChannel) = Unit

            override fun unregister(typeId: String) = Unit

            override fun find(typeId: String): NotificationChannel? = null

            override fun all(): List<NotificationChannel> = emptyList()
        }

    private fun simpleRegistry(): ConnectorRegistry {
        val entries = ConcurrentHashMap<String, Connector>()
        return object : ConnectorRegistry {
            override fun register(connector: Connector) {
                entries[connector.id] = connector
            }

            override fun unregister(id: String) {
                entries.remove(id)
            }

            override fun findById(id: String): Connector? = entries[id]

            override fun all(): List<Connector> = entries.values.toList()
        }
    }

    private fun fakeConnector(connectorId: String): Connector =
        object : Connector {
            override val id = connectorId
            override val name = connectorId

            override suspend fun search(
                query: String,
                request: PageRequest,
            ): Result<Pagination<SeriesSearchResult>> = Result.Failure(AppError.InternalError("fake"))

            override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> = Result.Failure(AppError.InternalError("fake"))

            override suspend fun fetchChapters(
                externalId: String,
                language: String,
            ): Result<List<ChapterMetadata>> = Result.Failure(AppError.InternalError("fake"))

            override suspend fun download(
                chapter: Chapter,
                format: ChapterFormat,
            ): Result<DownloadResult> = Result.Failure(AppError.InternalError("fake"))

            override fun supportedLanguages() = listOf("en")
        }

    private fun makeContext(connRegistry: ConnectorRegistry): ExtensionContext =
        object : ExtensionContext {
            override val httpClient get() = error("not needed in test")
            override val library get() = error("not needed in test")
            override val progress get() = error("not needed in test")
            override val system get() = error("not needed in test")
            override val connectorRegistry = connRegistry
            override val dataDir = tempDir
            override val enricherRegistry get() = error("not needed in test")
            override val notificationRegistry get() = error("not needed in test")
            override val config: dev.koenv.chaptervault.kernel.extension.ExtensionConfig =
                object : dev.koenv.chaptervault.kernel.extension.ExtensionConfig {
                    override fun get(key: String): String? = null
                }

            override fun rateLimiter(
                bucket: String,
                requestsPerSecond: Double,
            ) = error("not needed in test")

            override fun logger(name: String) = org.slf4j.LoggerFactory.getLogger(name)
        }

    private fun makeService(
        bundled: List<Extension> = emptyList(),
        connRegistry: ConnectorRegistry = simpleRegistry(),
        extRegistry: DefaultExtensionRegistry = DefaultExtensionRegistry(),
    ): Pair<ExtensionLoaderService, ConnectorRegistry> {
        val svc =
            ExtensionLoaderService(
                extensionRegistry = extRegistry,
                connectorRegistryDelegate = connRegistry,
                enricherRegistryDelegate = noopEnricherRegistry(),
                notificationRegistryDelegate = noopNotificationRegistry(),
                contextFactory = { _, _ -> makeContext(connRegistry) },
                externalLoader = ExternalExtensionLoader(extensionsDir = tempDir, serverVersion = "1.0.0"),
                bundledExtensions = bundled,
            )
        return svc to connRegistry
    }

    @Test
    fun `loadAll registers bundled extension as ENABLED`() {
        val connector = fakeConnector("test-connector")
        val adapter = ConnectorExtensionAdapter(connector)
        val extRegistry = DefaultExtensionRegistry()
        val (svc, connRegistry) = makeService(bundled = listOf(adapter), extRegistry = extRegistry)
        svc.loadAll()
        val entry = extRegistry.findById("test-connector")
        assertNotNull(entry)
        assertEquals(ExtensionStatus.ENABLED, entry.status)
        assertEquals(ExtensionSource.BUNDLED, entry.source)
        assertNotNull(connRegistry.findById("test-connector"))
    }

    @Test
    fun `disable unregisters connectors and sets DISABLED status`() {
        val connector = fakeConnector("my-connector")
        val adapter = ConnectorExtensionAdapter(connector)
        val extRegistry = DefaultExtensionRegistry()
        val connRegistry = simpleRegistry()
        val (svc, _) = makeService(bundled = listOf(adapter), connRegistry = connRegistry, extRegistry = extRegistry)
        svc.loadAll()
        svc.disable("my-connector")
        assertEquals(ExtensionStatus.DISABLED, extRegistry.findById("my-connector")!!.status)
        assertNull(connRegistry.findById("my-connector"))
    }

    @Test
    fun `enable after disable re-registers connectors and sets ENABLED status`() {
        val connector = fakeConnector("my-connector")
        val adapter = ConnectorExtensionAdapter(connector)
        val extRegistry = DefaultExtensionRegistry()
        val connRegistry = simpleRegistry()
        val (svc, _) = makeService(bundled = listOf(adapter), connRegistry = connRegistry, extRegistry = extRegistry)
        svc.loadAll()
        svc.disable("my-connector")
        svc.enable("my-connector")
        assertEquals(ExtensionStatus.ENABLED, extRegistry.findById("my-connector")!!.status)
        assertNotNull(connRegistry.findById("my-connector"))
    }

    @Test
    fun `listAll returns all registered entries`() {
        val (svc, _) = makeService(bundled = listOf(ConnectorExtensionAdapter(fakeConnector("a"))))
        svc.loadAll()
        assertEquals(1, svc.listAll().size)
    }

    @Test
    fun `findById returns null for unknown id`() {
        val (svc, _) = makeService()
        svc.loadAll()
        assertNull(svc.findById("unknown"))
    }

    @Test
    fun `extension whose onEnable throws is set to FAILED`() {
        val failingExt =
            object : Extension {
                override val id = "failing.ext"
                override val name = "Failing"
                override val version = "1.0.0"

                override fun capabilities() = emptySet<Capability>()

                override fun onEnable(ctx: ExtensionContext) = throw RuntimeException("boom")

                override fun onDisable() {}
            }
        val extRegistry = DefaultExtensionRegistry()
        val svc =
            ExtensionLoaderService(
                extensionRegistry = extRegistry,
                connectorRegistryDelegate = simpleRegistry(),
                enricherRegistryDelegate = noopEnricherRegistry(),
                notificationRegistryDelegate = noopNotificationRegistry(),
                contextFactory = { _, _ -> makeContext(simpleRegistry()) },
                externalLoader = ExternalExtensionLoader(tempDir, "1.0.0"),
                bundledExtensions = listOf(failingExt),
            )
        svc.loadAll()
        val entry = extRegistry.findById("failing.ext")
        assertNotNull(entry)
        assertEquals(ExtensionStatus.FAILED, entry.status)
        assertNotNull(entry.errorMessage)
    }
}
