package dev.koenv.chaptervault.infrastructure.extensions.loader

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
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

class ExtensionLoaderService(
    private val extensionRegistry: ExtensionRegistry,
    private val connectorRegistryDelegate: ConnectorRegistry,
    private val enricherRegistryDelegate: MetadataEnricherRegistry,
    private val notificationRegistryDelegate: NotificationChannelRegistry,
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
            classloaders[loaded.manifest.id] = loaded.classLoader
            enableAndRegister(loaded.extension, ExtensionSource.LOCAL, loaded.manifest, loaded.jarPath)
        }
    }

    fun getManifest(extensionId: String): ExtensionManifest? = manifests[extensionId]

    @Synchronized
    override fun enable(id: String) {
        val entry =
            extensionRegistry.findById(id) ?: run {
                log.warn("enable: extension '$id' not found")
                return
            }
        if (entry.status == ExtensionStatus.ENABLED) return
        enableAndRegister(entry.extension, entry.source, manifests[id], entry.jarPath)
    }

    @Synchronized
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
        entry.registeredEnricherIds.forEach { enricherId ->
            enricherRegistryDelegate.unregister(enricherId)
        }
        entry.registeredChannelTypeIds.forEach { typeId ->
            notificationRegistryDelegate.unregister(typeId)
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

    @Synchronized
    override fun unload(id: String) {
        val entry = extensionRegistry.findById(id) ?: return
        if (entry.source == ExtensionSource.BUNDLED) {
            log.warn("Cannot unload bundled extension '$id'")
            return
        }
        if (entry.status == ExtensionStatus.ENABLED) {
            disable(id)
        }
        classloaders.remove(id)?.close()
        extensionRegistry.updateStatus(id, ExtensionStatus.UNLOADED)
        log.info("Extension '$id' unloaded")
    }

    @Synchronized
    override fun reload(id: String) {
        val entry =
            extensionRegistry.findById(id) ?: run {
                log.warn("reload: extension '$id' not found")
                return
            }
        if (entry.source == ExtensionSource.BUNDLED) {
            log.warn("Cannot reload bundled extension '$id'")
            return
        }
        val jarPath =
            entry.jarPath ?: run {
                log.error("reload: no JAR path recorded for '$id'")
                return
            }
        unload(id)
        val loaded =
            externalLoader.loadSingle(jarPath) ?: run {
                log.error("reload: failed to load JAR for '$id' at $jarPath")
                extensionRegistry.updateStatus(id, ExtensionStatus.FAILED, "reload failed: JAR at $jarPath could not be loaded")
                return
            }
        classloaders[loaded.manifest.id] = loaded.classLoader
        enableAndRegister(loaded.extension, ExtensionSource.LOCAL, loaded.manifest, loaded.jarPath)
    }

    private fun enableAndRegister(
        extension: Extension,
        source: ExtensionSource,
        manifest: ExtensionManifest? = null,
        jarPath: Path? = null,
    ) {
        val canonicalId = manifest?.id ?: extension.id
        val dataDir = extensionsDataRoot.resolve(canonicalId)
        val trackingConnectors = TrackingConnectorRegistry(connectorRegistryDelegate)
        val trackingEnrichers = TrackingEnricherRegistry(enricherRegistryDelegate)
        val trackingNotifications = TrackingNotificationRegistry(notificationRegistryDelegate)

        manifest?.let { manifests[canonicalId] = it }

        extensionRegistry.register(
            ExtensionEntry(extension = extension, id = canonicalId, status = ExtensionStatus.LOADING, source = source),
        )

        try {
            Files.createDirectories(dataDir)

            // Extract bundled config.yml from JAR if not already present (LOCAL extensions only)
            if (jarPath != null) {
                val configFile = dataDir.resolve("config.yml").toFile()
                if (!configFile.exists()) {
                    try {
                        extractBundledConfig(jarPath, configFile)
                    } catch (e: Exception) {
                        extensionRegistry.updateStatus(
                            canonicalId,
                            ExtensionStatus.FAILED,
                            "Failed to extract bundled config: ${e.message}",
                        )
                        log.error("Extension '$canonicalId' failed to extract bundled config.yml: ${e.message}")
                        return
                    }
                }
            }

            // contextFactory is called after extraction so that DefaultManagedConfig's eager
            // YamlConfig.load() in its constructor reads the extracted defaults, not a missing file.
            // It is also inside the try block so that a malformed config.yml (which throws in
            // YamlConfig.load()) marks only this extension as FAILED rather than aborting all
            // remaining extensions.
            val baseContext = contextFactory(canonicalId, dataDir)

            extension.onEnable(buildContextWithTracking(baseContext, trackingConnectors, trackingEnrichers, trackingNotifications))
            validateCapabilities(extension, trackingConnectors, trackingEnrichers, trackingNotifications)
            extensionRegistry.register(
                ExtensionEntry(
                    extension = extension,
                    id = canonicalId,
                    status = ExtensionStatus.ENABLED,
                    source = source,
                    jarPath = jarPath,
                    registeredConnectorIds = trackingConnectors.registeredIds,
                    registeredEnricherIds = trackingEnrichers.registeredIds,
                    registeredChannelTypeIds = trackingNotifications.registeredTypeIds,
                ),
            )
            log.info("Extension '$canonicalId' enabled (source=$source)")
        } catch (e: Throwable) {
            extensionRegistry.updateStatus(canonicalId, ExtensionStatus.FAILED, e.message)
            log.error("Extension '$canonicalId' failed to enable: ${e.message}")
        }
    }

    private fun extractBundledConfig(
        jarPath: Path,
        targetFile: java.io.File,
    ) {
        JarFile(jarPath.toFile()).use { jar ->
            val entry = jar.getJarEntry("config.yml") ?: return
            targetFile.parentFile?.mkdirs()
            jar.getInputStream(entry).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun buildContextWithTracking(
        base: ExtensionContext,
        trackingConnectors: TrackingConnectorRegistry,
        trackingEnrichers: TrackingEnricherRegistry,
        trackingNotifications: TrackingNotificationRegistry,
    ): ExtensionContext =
        object : ExtensionContext by base {
            override val connectorRegistry = trackingConnectors
            override val enricherRegistry = trackingEnrichers
            override val notificationRegistry = trackingNotifications
        }

    private fun validateCapabilities(
        extension: Extension,
        connectors: TrackingConnectorRegistry,
        enrichers: TrackingEnricherRegistry,
        notifications: TrackingNotificationRegistry,
    ) {
        val declared = extension.capabilities()
        if (Capability.CanFetchSeries in declared && connectors.registeredIds.isEmpty()) {
            log.warn("Extension '${extension.id}' declares CanFetchSeries but registered no connectors")
        }
        if (Capability.CanEnrichMetadata in declared && enrichers.registeredIds.isEmpty()) {
            log.warn("Extension '${extension.id}' declares CanEnrichMetadata but registered no enrichers")
        }
        if (Capability.CanSendNotifications in declared && notifications.registeredTypeIds.isEmpty()) {
            log.warn("Extension '${extension.id}' declares CanSendNotifications but registered no channels")
        }
    }
}
