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
import dev.koenv.chaptervault.kernel.extension.ExtensionEntry
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals

class ExtensionLoaderServiceUnloadTest {
    @TempDir
    lateinit var tempDir: Path

    private fun buildFixture(): ExtensionLoaderServiceFixture {
        val bundledId = "bundled-test-connector"
        val localId = "local-test-extension"

        val connector = fakeConnector(bundledId)
        val adapter = ConnectorExtensionAdapter(connector)
        val extRegistry = DefaultExtensionRegistry()
        val connRegistry = simpleConnectorRegistry()
        val svc =
            ExtensionLoaderService(
                extensionRegistry = extRegistry,
                connectorRegistryDelegate = connRegistry,
                enricherRegistryDelegate = noopEnricherRegistry(),
                notificationRegistryDelegate = noopNotificationChannelRegistry(),
                contextFactory = { _, _ -> makeContext(connRegistry, tempDir) },
                externalLoader = ExternalExtensionLoader(extensionsDir = tempDir, serverVersion = "1.0.0"),
                bundledExtensions = listOf(adapter),
            )

        // Register a LOCAL extension entry directly so we can test unloading without a JAR file.
        extRegistry.register(
            ExtensionEntry(
                extension = fakeExtension(localId),
                status = ExtensionStatus.ENABLED,
                source = ExtensionSource.LOCAL,
                jarPath = null,
            ),
        )

        return ExtensionLoaderServiceFixture(service = svc, bundledId = bundledId, localId = localId)
    }

    @Test
    fun `unload sets status to UNLOADED`() {
        val fixture = buildFixture()
        fixture.service.loadAll()
        val id = fixture.localId

        fixture.service.disable(id)
        fixture.service.unload(id)

        assertEquals(ExtensionStatus.UNLOADED, fixture.service.findById(id)?.status)
    }

    @Test
    fun `unload on bundled extension is a no-op`() {
        val fixture = buildFixture()
        fixture.service.loadAll()
        val id = fixture.bundledId

        fixture.service.unload(id)

        assertEquals(ExtensionStatus.ENABLED, fixture.service.findById(id)?.status)
    }
}

data class ExtensionLoaderServiceFixture(
    val service: ExtensionLoaderService,
    val bundledId: String,
    val localId: String,
)

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

private fun fakeExtension(extensionId: String): Extension =
    object : Extension {
        override val id = extensionId
        override val name = extensionId
        override val version = "1.0.0"

        override fun capabilities(): Set<Capability> = emptySet()

        override fun onEnable(context: ExtensionContext) {}

        override fun onDisable() {}
    }

private fun simpleConnectorRegistry(): ConnectorRegistry {
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

private fun noopEnricherRegistry(): MetadataEnricherRegistry =
    object : MetadataEnricherRegistry {
        override fun register(
            enricher: MetadataEnricher,
            priority: Int,
        ) = Unit

        override fun unregister(id: String) = Unit

        override fun all(): List<MetadataEnricher> = emptyList()
    }

private fun noopNotificationChannelRegistry(): NotificationChannelRegistry =
    object : NotificationChannelRegistry {
        override fun register(channel: NotificationChannel) = Unit

        override fun unregister(typeId: String) = Unit

        override fun find(typeId: String): NotificationChannel? = null

        override fun all(): List<NotificationChannel> = emptyList()
    }

private fun makeContext(
    connRegistry: ConnectorRegistry,
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
