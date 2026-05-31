package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionEntry
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionSource
import dev.koenv.chaptervault.kernel.extension.ExtensionStatus
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class ExtensionLoaderService(
    private val extensionRegistry: ExtensionRegistry,
    private val connectorRegistryDelegate: ConnectorRegistry,
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
        val tracking = TrackingConnectorRegistry(connectorRegistryDelegate)
        val baseContext = contextFactory(extension.id, dataDir)

        manifest?.let { manifests[extension.id] = it }

        extensionRegistry.register(
            ExtensionEntry(extension = extension, status = ExtensionStatus.LOADING, source = source),
        )

        try {
            Files.createDirectories(dataDir)
            extension.onEnable(buildContextWithTracking(baseContext, tracking))
            validateCapabilities(extension)
            extensionRegistry.register(
                ExtensionEntry(
                    extension = extension,
                    status = ExtensionStatus.ENABLED,
                    source = source,
                    jarPath = jarPath,
                    registeredConnectorIds = tracking.registeredIds,
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
        tracking: TrackingConnectorRegistry,
    ): ExtensionContext =
        object : ExtensionContext by base {
            override val connectorRegistry = tracking
        }

    private fun validateCapabilities(extension: Extension) {
        // TODO(Plan2): validate registered vs declared capabilities (spec §5)
    }
}
