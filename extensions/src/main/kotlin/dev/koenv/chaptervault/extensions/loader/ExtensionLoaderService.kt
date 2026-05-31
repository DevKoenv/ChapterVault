package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionEntry
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionSource
import dev.koenv.chaptervault.kernel.extension.ExtensionStatus
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class ExtensionLoaderService(
    private val extensionRegistry: ExtensionRegistry,
    private val connectorRegistryDelegate: ConnectorRegistry,
    private val enricherRegistryDelegate: MetadataEnricherRegistry,
    private val contextFactory: (extensionId: String, extensionDataDir: Path) -> ExtensionContext,
    private val externalLoader: ExternalExtensionLoader,
    private val bundledExtensions: List<Extension>,
) : ExtensionManager {
    private val extensionsDataRoot: Path get() = externalLoader.extensionsDir
    private val classloaders = ConcurrentHashMap<String, URLClassLoader>()
    private val manifests = ConcurrentHashMap<String, ExtensionManifest>()

    private val log = LoggerFactory.getLogger(ExtensionLoaderService::class.java)

    fun loadAll() {
        bundledExtensions.forEach { ext ->
            enableAndRegister(ext, ExtensionSource.BUNDLED)
        }
        externalLoader.loadAll().forEach { loaded ->
            classloaders[loaded.extension.id] = loaded.classLoader
            enableAndRegister(loaded.extension, ExtensionSource.LOCAL, loaded.manifest, loaded.jarPath)
        }
    }

    fun getManifest(extensionId: String): ExtensionManifest? = manifests[extensionId]

    override fun enable(id: String) {
        val entry =
            extensionRegistry.findById(id) ?: run {
                log.warn("enable: extension '$id' not found")
                return
            }
        if (entry.status == ExtensionStatus.ENABLED) return
        enableAndRegister(entry.extension, entry.source, manifests[id], entry.jarPath)
    }

    override fun disable(id: String) {
        val entry =
            extensionRegistry.findById(id) ?: run {
                log.warn("disable: extension '$id' not found")
                return
            }
        if (entry.status != ExtensionStatus.ENABLED) return
        entry.registeredConnectorIds.forEach { connectorId ->
            connectorRegistryDelegate.unregister(connectorId)
        }
        try {
            entry.extension.onDisable()
        } catch (e: Exception) {
            log.warn("onDisable threw for '${entry.extension.id}': ${e.message}")
        }
        extensionRegistry.updateStatus(id, ExtensionStatus.DISABLED)
        log.info("Extension '${entry.extension.id}' disabled")
    }

    override fun listAll(): List<ExtensionEntry> = extensionRegistry.all()

    override fun findById(id: String): ExtensionEntry? = extensionRegistry.findById(id)

    private fun enableAndRegister(
        extension: Extension,
        source: ExtensionSource,
        manifest: ExtensionManifest? = null,
        jarPath: Path? = null,
    ) {
        val dataDir = extensionsDataRoot.resolve(extension.id)
        val trackingConnectors = TrackingConnectorRegistry(connectorRegistryDelegate)
        val trackingEnrichers = TrackingEnricherRegistry(enricherRegistryDelegate)
        val baseContext = contextFactory(extension.id, dataDir)

        manifest?.let { manifests[extension.id] = it }

        extensionRegistry.register(
            ExtensionEntry(extension = extension, status = ExtensionStatus.LOADING, source = source),
        )

        try {
            Files.createDirectories(dataDir)
            extension.onEnable(buildContextWithTracking(baseContext, trackingConnectors, trackingEnrichers))
            validateCapabilities(extension, trackingConnectors, trackingEnrichers)
            extensionRegistry.register(
                ExtensionEntry(
                    extension = extension,
                    status = ExtensionStatus.ENABLED,
                    source = source,
                    jarPath = jarPath,
                    registeredConnectorIds = trackingConnectors.registeredIds,
                    registeredEnricherIds = trackingEnrichers.registeredIds,
                ),
            )
            log.info("Extension '${extension.id}' enabled (source=$source)")
        } catch (e: Exception) {
            extensionRegistry.updateStatus(extension.id, ExtensionStatus.FAILED, e.message)
            log.error("Extension '${extension.id}' failed to enable: ${e.message}")
        }
    }

    private fun buildContextWithTracking(
        base: ExtensionContext,
        trackingConnectors: TrackingConnectorRegistry,
        trackingEnrichers: TrackingEnricherRegistry,
    ): ExtensionContext =
        object : ExtensionContext by base {
            override val connectorRegistry = trackingConnectors
            override val enricherRegistry = trackingEnrichers
        }

    private fun validateCapabilities(
        extension: Extension,
        connectors: TrackingConnectorRegistry,
        enrichers: TrackingEnricherRegistry,
    ) {
        val declared = extension.capabilities()
        if (Capability.CanFetchSeries in declared && connectors.registeredIds.isEmpty()) {
            log.warn("Extension '${extension.id}' declares CanFetchSeries but registered no connectors")
        }
        if (Capability.CanEnrichMetadata in declared && enrichers.registeredIds.isEmpty()) {
            log.warn("Extension '${extension.id}' declares CanEnrichMetadata but registered no enrichers")
        }
    }
}
