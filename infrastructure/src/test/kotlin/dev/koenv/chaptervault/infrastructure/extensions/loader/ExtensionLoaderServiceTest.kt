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
import dev.koenv.chaptervault.kernel.extension.ManagedConfig
import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.kernel.extension.YamlConfig
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            override val config get() = error("not needed in test")
            override val enricherRegistry get() = error("not needed in test")
            override val notificationRegistry get() = error("not needed in test")

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

    private fun buildJarWithConfig(
        manifestYaml: String,
        configYaml: String?,
        vararg classes: Class<*>,
    ): Path {
        val jar = Files.createTempFile(tempDir, "test-ext", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            jos.putNextEntry(JarEntry("extension.yaml"))
            jos.write(manifestYaml.toByteArray())
            jos.closeEntry()
            if (configYaml != null) {
                jos.putNextEntry(JarEntry("config.yml"))
                jos.write(configYaml.toByteArray())
                jos.closeEntry()
            }
            for (cls in classes) {
                val resourcePath = cls.name.replace('.', '/') + ".class"
                val bytes = cls.getResourceAsStream("/$resourcePath")!!.readBytes()
                jos.putNextEntry(JarEntry(resourcePath))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return jar
    }

    private val testExtManifest =
        """
        id: test-extension
        name: Test Extension
        version: 1.0.0
        minServerVersion: 1.0.0
        description: Test
        author: Test
        capabilities:
          - connector
        entryPoint: "dev.koenv.chaptervault.infrastructure.extensions.loader.TestExtension"
        """.trimIndent()

    @Test
    fun `extracts bundled config yml from JAR to dataDir`() {
        val jar =
            buildJarWithConfig(
                testExtManifest,
                "baseUrl: https://example.com\n",
                TestExtension::class.java,
            )
        Files.move(jar, tempDir.resolve("test-extension-1.0.jar"))

        val extRegistry = DefaultExtensionRegistry()
        val (svc, _) = makeService(extRegistry = extRegistry)
        svc.loadAll()

        val configFile = tempDir.resolve("test-extension").resolve("config.yml").toFile()
        assertTrue(configFile.exists(), "config.yml should be extracted to dataDir")
        assertTrue(configFile.readText().contains("example.com"))
    }

    @Test
    fun `does not overwrite existing config yml during extraction`() {
        val jar =
            buildJarWithConfig(
                testExtManifest,
                "baseUrl: https://from-jar.com\n",
                TestExtension::class.java,
            )
        Files.move(jar, tempDir.resolve("test-extension-1.0.jar"))

        val dataDir = tempDir.resolve("test-extension").also { Files.createDirectories(it) }
        val configFile = dataDir.resolve("config.yml").toFile()
        configFile.writeText("baseUrl: https://already-here.com\n")

        val (svc, _) = makeService()
        svc.loadAll()

        assertEquals("baseUrl: https://already-here.com\n", configFile.readText())
    }

    @Test
    fun `skips extraction when JAR has no config yml`() {
        val jar = buildJarWithConfig(testExtManifest, null, TestExtension::class.java)
        Files.move(jar, tempDir.resolve("test-extension-1.0.jar"))

        val (svc, _) = makeService()
        svc.loadAll()

        val configFile = tempDir.resolve("test-extension").resolve("config.yml").toFile()
        assertFalse(configFile.exists(), "config.yml should not be created when JAR has none")
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
                override val id = "failing-ext"
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
        val entry = extRegistry.findById("failing-ext")
        assertNotNull(entry)
        assertEquals(ExtensionStatus.FAILED, entry.status)
        assertNotNull(entry.errorMessage)
    }

    private fun extensionRegistering(
        connectorId: String,
        thenFail: Boolean,
    ) = object : Extension {
        override val id = "registering-ext"
        override val name = "Registering"
        override val version = "1.0.0"

        override fun capabilities() = setOf(Capability.CanFetchSeries)

        override fun onEnable(ctx: ExtensionContext) {
            ctx.connectorRegistry.register(fakeConnector(connectorId))
            if (thenFail) error("fails after registering")
        }

        override fun onDisable() {}
    }

    @Test
    fun `an extension that fails while enabling leaves nothing registered`() {
        val connRegistry = simpleRegistry()
        val extRegistry = DefaultExtensionRegistry()
        val (svc, _) =
            makeService(
                bundled = listOf(extensionRegistering("leaky", thenFail = true)),
                connRegistry = connRegistry,
                extRegistry = extRegistry,
            )

        svc.loadAll()

        assertEquals(ExtensionStatus.FAILED, extRegistry.findById("registering-ext")!!.status)
        assertNull(connRegistry.findById("leaky"), "the connector of the failed extension must not stay registered")
    }

    @Test
    fun `an extension cannot take over the id of a connector that is already registered`() {
        val builtIn = fakeConnector("mangadex")
        val connRegistry = simpleRegistry().also { it.register(builtIn) }
        val extRegistry = DefaultExtensionRegistry()
        val (svc, _) =
            makeService(
                bundled = listOf(extensionRegistering("mangadex", thenFail = false)),
                connRegistry = connRegistry,
                extRegistry = extRegistry,
            )

        svc.loadAll()

        assertEquals(ExtensionStatus.FAILED, extRegistry.findById("registering-ext")!!.status)
        assertTrue(connRegistry.findById("mangadex") === builtIn, "the built-in connector must stay")
        svc.disable("registering-ext")
        assertTrue(connRegistry.findById("mangadex") === builtIn, "disabling the extension must not remove the built-in connector")
    }

    @Test
    fun `extracted config yml is readable by extension on first enable`() {
        ConfigReadingTestExtension.capturedBaseUrl = null // reset before test

        val manifest =
            """
            id: test-extension
            name: Test Extension
            version: 1.0.0
            minServerVersion: 1.0.0
            description: Test
            author: Test
            capabilities:
              - connector
            entryPoint: "dev.koenv.chaptervault.infrastructure.extensions.loader.ConfigReadingTestExtension"
            """.trimIndent()

        val jar = buildJarWithConfig(manifest, "baseUrl: https://example.com\n", ConfigReadingTestExtension::class.java)
        Files.move(jar, tempDir.resolve("test-extension-1.0.jar"))

        val extRegistry = DefaultExtensionRegistry()
        val svc =
            ExtensionLoaderService(
                extensionRegistry = extRegistry,
                connectorRegistryDelegate = simpleRegistry(),
                enricherRegistryDelegate = noopEnricherRegistry(),
                notificationRegistryDelegate = noopNotificationRegistry(),
                contextFactory = { _, dir ->
                    // Inline ManagedConfig backed by YamlConfig — mirrors DefaultManagedConfig from
                    // :apps:server but avoids a cross-module dependency in the infrastructure tests.
                    val yamlCfg = YamlConfig(dir.resolve("config.yml").toFile()).load()
                    val config =
                        object : ManagedConfig {
                            override fun reload() {
                                yamlCfg.load()
                            }

                            override fun save() = yamlCfg.save()

                            override fun getString(
                                path: String,
                                default: String?,
                            ) = yamlCfg.getString(path, default)

                            override fun getInt(
                                path: String,
                                default: Int?,
                            ) = yamlCfg.getInt(path, default)

                            override fun getBoolean(
                                path: String,
                                default: Boolean?,
                            ) = yamlCfg.getBoolean(path, default)

                            override fun getDouble(
                                path: String,
                                default: Double?,
                            ) = yamlCfg.getDouble(path, default)

                            override fun get(path: String) = yamlCfg.get(path)

                            override fun set(
                                path: String,
                                value: Any?,
                            ) = yamlCfg.set(path, value)

                            override fun remove(path: String) = yamlCfg.remove(path)

                            override fun contains(path: String) = yamlCfg.contains(path)
                        }
                    object : ExtensionContext {
                        override val httpClient get() = error("not needed in test")
                        override val library get() = error("not needed in test")
                        override val progress get() = error("not needed in test")
                        override val system get() = error("not needed in test")
                        override val connectorRegistry get() = error("not needed in test")
                        override val dataDir = dir
                        override val config = config
                        override val enricherRegistry get() = error("not needed in test")
                        override val notificationRegistry get() = error("not needed in test")

                        override fun rateLimiter(
                            bucket: String,
                            requestsPerSecond: Double,
                        ) = error("not needed in test")

                        override fun logger(name: String) = org.slf4j.LoggerFactory.getLogger(name)
                    }
                },
                externalLoader = ExternalExtensionLoader(extensionsDir = tempDir, serverVersion = "1.0.0"),
                bundledExtensions = emptyList(),
            )
        svc.loadAll()

        assertEquals(ExtensionStatus.ENABLED, extRegistry.findById("test-extension")?.status)
        assertEquals("https://example.com", ConfigReadingTestExtension.capturedBaseUrl)
    }
}

/**
 * Extension that records the value of `baseUrl` from its config in [onEnable].
 * Used by the "extracted config yml is readable by extension on first enable" test to prove
 * that config extraction happens before [ExtensionLoaderService] builds the context.
 *
 * Loaded via URLClassLoader (parent-delegation), so [capturedBaseUrl] in the companion object
 * is shared between the test and the loaded extension instance.
 */
class ConfigReadingTestExtension : Extension {
    override val id = "test-extension"
    override val name = "Test Extension (Config Reading)"
    override val version = "1.0.0"

    override fun capabilities() = emptySet<Capability>()

    override fun onEnable(context: ExtensionContext) {
        capturedBaseUrl = context.config.getString("baseUrl")
    }

    override fun onDisable() {}

    companion object {
        var capturedBaseUrl: String? = null
    }
}
