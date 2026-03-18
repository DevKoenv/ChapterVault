package dev.koenv.chaptervault.app

import dev.koenv.chaptervault.core.addon.AddonContext
import dev.koenv.chaptervault.core.addon.AddonEntrypoint
import dev.koenv.chaptervault.core.addon.AddonError
import dev.koenv.chaptervault.core.addon.AddonInfo
import dev.koenv.chaptervault.core.addon.AddonManifest
import dev.koenv.chaptervault.core.addon.AddonRegistry
import dev.koenv.chaptervault.core.addon.AddonState
import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.core.execution.Executor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import java.time.Instant

private val logger = KotlinLogging.logger {}

class AddonRegistryImpl(
    private val addonsDir: File,
    private val addonsDataPath: String,
    private val executor: Executor,
    private val connectorRegistry: ConnectorRegistry
) : AddonRegistry {

    companion object {
        const val CURRENT_API_VERSION = 1
        const val MINIMUM_SUPPORTED_VERSION = 1
    }

    private class LoadedEntry(
        val manifest: AddonManifest,
        val jar: File,
        val classLoader: URLClassLoader,
        val addon: AddonEntrypoint?,
        var context: AddonContextImpl?,
        var state: AddonState
    )

    private inner class AddonContextImpl(val manifest: AddonManifest) : AddonContext {
        override val addonId: String = manifest.id
        override val addonName: String = manifest.name
        override val addonVersion: String = manifest.version
        override val executor: Executor = this@AddonRegistryImpl.executor
        override val dataDir: File
            get() = File("$addonsDataPath/${manifest.id}/data").also { it.mkdirs() }
        val connectors = mutableListOf<Connector>()
        override fun registerConnector(connector: Connector) {
            connectors += connector
        }
    }

    private val entries = LinkedHashMap<String, LoadedEntry>()
    private val errors = mutableMapOf<String, MutableList<AddonError>>()

    private fun recordError(id: String, phase: String, message: String, stackTrace: String?) {
        errors.getOrPut(id) { mutableListOf() }.add(
            AddonError(phase = phase, message = message, stackTrace = stackTrace, occurredAt = Instant.now())
        )
    }

    /**
     * Scans [addonsDir] for JAR files, resolves dependency order, calls [AddonEntrypoint.onLoad]
     * on every addon, then [AddonEntrypoint.onEnable] to collect registrations.
     * Each step is individually fault-isolated — a failure marks the addon as FAILED and continues.
     */
    @Synchronized
    fun load() {
        if (!addonsDir.exists() || !addonsDir.isDirectory) return
        val jars = addonsDir.listFiles { f -> f.extension == "jar" }
            ?.takeIf { it.isNotEmpty() } ?: return

        data class ParsedAddon(val manifest: AddonManifest, val classLoader: URLClassLoader, val jar: File)

        val parsed = mutableMapOf<String, ParsedAddon>()

        for (jar in jars) {
            logger.info { "Loading addon JAR: ${jar.name}" }
            try {
                val classLoader = URLClassLoader(
                    arrayOf(jar.toURI().toURL()),
                    Thread.currentThread().contextClassLoader
                )
                val manifestStream = classLoader.getResourceAsStream("addon.yml")
                if (manifestStream == null) {
                    logger.error { "No addon.yml found in ${jar.name} — skipping" }
                    classLoader.close()
                    continue
                }
                val manifest = manifestStream.use { parseManifest(it) }

                if (manifest.apiVersion < MINIMUM_SUPPORTED_VERSION) {
                    logger.error {
                        "Addon '${manifest.name}' reports apiVersion=${manifest.apiVersion}, " +
                            "minimum is $MINIMUM_SUPPORTED_VERSION — skipping"
                    }
                    classLoader.close()
                    continue
                }

                if (manifest.apiVersion > CURRENT_API_VERSION) {
                    logger.error {
                        "Addon '${manifest.name}' requires apiVersion=${manifest.apiVersion}, " +
                            "host supports up to $CURRENT_API_VERSION — skipping"
                    }
                    classLoader.close()
                    continue
                }

                if (parsed.containsKey(manifest.id)) {
                    logger.error { "Duplicate addon id '${manifest.id}' in ${jar.name} — skipping" }
                    classLoader.close()
                    continue
                }

                parsed[manifest.id] = ParsedAddon(manifest, classLoader, jar)
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse addon from ${jar.name}: ${e.message}" }
            }
        }

        // Validate required dependencies
        val validIds = mutableSetOf<String>()
        for ((id, addon) in parsed) {
            val missing = addon.manifest.depends.filter { it !in parsed }
            if (missing.isNotEmpty()) {
                val msg = "Missing required dependencies: ${missing.joinToString()}"
                logger.error { "Addon '${addon.manifest.name}' ($id): $msg" }
                recordError(id, "load", msg, null)
                entries[id] = LoadedEntry(
                    manifest = addon.manifest,
                    jar = addon.jar,
                    classLoader = addon.classLoader,
                    addon = null,
                    context = null,
                    state = AddonState.FAILED
                )
            } else {
                validIds.add(id)
            }
        }

        // Topological sort — includes present optional deps as ordering edges
        val dependsOn = validIds.associateWith { id ->
            val manifest = parsed[id]!!.manifest
            (manifest.depends + manifest.optionalDepends.filter { it in validIds })
                .filter { it in validIds }
        }
        val (sorted, cyclic) = topoSort(validIds, dependsOn)

        for (id in cyclic) {
            val addon = parsed[id]!!
            val msg = "Circular dependency detected"
            logger.error { "Addon '${addon.manifest.name}' ($id): $msg" }
            recordError(id, "load", msg, null)
            entries[id] = LoadedEntry(
                manifest = addon.manifest,
                jar = addon.jar,
                classLoader = addon.classLoader,
                addon = null,
                context = null,
                state = AddonState.FAILED
            )
        }

        // Phase 1: instantiate and call onLoad
        for (id in sorted) {
            val addon = parsed[id]!!
            try {
                val instance = addon.classLoader.loadClass(addon.manifest.main)
                    .getDeclaredConstructor()
                    .newInstance() as AddonEntrypoint
                instance.onLoad()
                entries[id] = LoadedEntry(
                    manifest = addon.manifest,
                    jar = addon.jar,
                    classLoader = addon.classLoader,
                    addon = instance,
                    context = null,
                    state = AddonState.LOADED
                )
                logger.info { "Loaded addon '${addon.manifest.name}' v${addon.manifest.version}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load addon '${addon.manifest.name}': ${e.message}" }
                recordError(id, "load", e.message ?: "Unknown error", e.stackTraceToString())
                entries[id] = LoadedEntry(
                    manifest = addon.manifest,
                    jar = addon.jar,
                    classLoader = addon.classLoader,
                    addon = null,
                    context = null,
                    state = AddonState.FAILED
                )
            }
        }

        // Phase 2: call onEnable and register connectors
        for (id in sorted) {
            val entry = entries[id] ?: continue
            if (entry.state != AddonState.LOADED) continue
            val addonInstance = entry.addon ?: continue
            val context = AddonContextImpl(entry.manifest)
            try {
                addonInstance.onEnable(context)
                context.connectors.forEach { connectorRegistry.register(it, id) }
                entry.context = context
                entry.state = AddonState.ENABLED
                logger.info {
                    "Enabled addon '${entry.manifest.name}' v${entry.manifest.version} — " +
                        "registered ${context.connectors.size} connector(s)"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to enable addon '${entry.manifest.name}': ${e.message}" }
                recordError(id, "enable", e.message ?: "Unknown error", e.stackTraceToString())
                entry.state = AddonState.FAILED
            }
        }
    }

    @Synchronized
    override fun enableAddon(id: String) {
        val entry = entries[id] ?: throw IllegalArgumentException("Addon not found: $id")
        if (entry.state != AddonState.DISABLED) {
            throw IllegalStateException("Addon '$id' is not DISABLED (current state: ${entry.state})")
        }
        val addonInstance = entry.addon
            ?: throw IllegalStateException("Addon '$id' cannot be enabled (no entrypoint)")
        val context = AddonContextImpl(entry.manifest)
        try {
            addonInstance.onEnable(context)
            context.connectors.forEach { connectorRegistry.register(it, id) }
            entry.context = context
            entry.state = AddonState.ENABLED
            logger.info {
                "Enabled addon '${entry.manifest.name}' v${entry.manifest.version} — " +
                    "registered ${context.connectors.size} connector(s)"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to enable addon '${entry.manifest.name}': ${e.message}" }
            recordError(id, "enable", e.message ?: "Unknown error", e.stackTraceToString())
            entry.state = AddonState.FAILED
            throw e
        }
    }

    @Synchronized
    override fun disableAddon(id: String) {
        val entry = entries[id] ?: throw IllegalArgumentException("Addon not found: $id")
        if (entry.state != AddonState.ENABLED) {
            throw IllegalStateException("Addon '$id' is not ENABLED (current state: ${entry.state})")
        }
        try {
            entry.addon?.onDisable()
        } catch (e: Exception) {
            logger.error(e) { "Error during onDisable for addon '${entry.manifest.name}': ${e.message}" }
            recordError(id, "disable", e.message ?: "Unknown error", e.stackTraceToString())
        }
        connectorRegistry.unregisterByAddon(id)
        entry.context = null
        entry.state = AddonState.DISABLED
        logger.info { "Disabled addon '${entry.manifest.name}'" }
    }

    @Synchronized
    override fun reloadAddon(id: String) {
        val entry = entries[id] ?: throw IllegalArgumentException("Addon not found: $id")

        if (entry.state == AddonState.ENABLED) {
            try { disableAddon(id) } catch (_: Exception) {}
        }

        val jar = entry.jar
        try { entry.classLoader.close() } catch (_: Exception) {}
        entries.remove(id)
        errors.remove(id)

        if (!jar.exists()) {
            val msg = "Addon JAR no longer exists: ${jar.absolutePath}"
            logger.error { msg }
            recordError(id, "reload", msg, null)
            entries[id] = LoadedEntry(
                manifest = entry.manifest,
                jar = jar,
                classLoader = URLClassLoader(emptyArray(), Thread.currentThread().contextClassLoader),
                addon = null,
                context = null,
                state = AddonState.FAILED
            )
            return
        }

        try {
            val classLoader = URLClassLoader(
                arrayOf(jar.toURI().toURL()),
                Thread.currentThread().contextClassLoader
            )
            val manifestStream = classLoader.getResourceAsStream("addon.yml")
                ?: throw IllegalStateException("No addon.yml in ${jar.name}")
            val manifest = manifestStream.use { parseManifest(it) }

            if (manifest.id != id) {
                throw IllegalStateException("Addon id changed from '$id' to '${manifest.id}' during reload")
            }

            val instance = classLoader.loadClass(manifest.main)
                .getDeclaredConstructor()
                .newInstance() as AddonEntrypoint
            instance.onLoad()

            val newEntry = LoadedEntry(
                manifest = manifest,
                jar = jar,
                classLoader = classLoader,
                addon = instance,
                context = null,
                state = AddonState.LOADED
            )
            entries[id] = newEntry
            logger.info { "Reloaded addon '${manifest.name}' v${manifest.version} (phase 1)" }

            val context = AddonContextImpl(manifest)
            instance.onEnable(context)
            context.connectors.forEach { connectorRegistry.register(it, id) }
            newEntry.context = context
            newEntry.state = AddonState.ENABLED
            logger.info {
                "Enabled addon '${manifest.name}' v${manifest.version} after reload — " +
                    "registered ${context.connectors.size} connector(s)"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to reload addon '$id': ${e.message}" }
            recordError(id, "reload", e.message ?: "Unknown error", e.stackTraceToString())
            if (id !in entries) {
                entries[id] = LoadedEntry(
                    manifest = entry.manifest,
                    jar = jar,
                    classLoader = URLClassLoader(emptyArray(), Thread.currentThread().contextClassLoader),
                    addon = null,
                    context = null,
                    state = AddonState.FAILED
                )
            } else {
                entries[id]!!.state = AddonState.FAILED
            }
        }
    }

    @Synchronized
    override fun removeAddon(id: String) {
        val entry = entries[id] ?: return
        if (entry.state == AddonState.ENABLED) {
            try { disableAddon(id) } catch (_: Exception) {}
        }
        try { entry.classLoader.close() } catch (_: Exception) {}
        entries.remove(id)
        errors.remove(id)
        logger.info { "Removed addon '${entry.manifest.name}'" }
    }

    /**
     * Calls [AddonEntrypoint.onDisable] on all enabled addons in reverse load order.
     * Safe to call even if [load] was never called.
     */
    @Synchronized
    fun shutdown() {
        val ids = entries.keys.toList().reversed()
        for (id in ids) {
            val entry = entries[id] ?: continue
            if (entry.state == AddonState.ENABLED) {
                try { disableAddon(id) } catch (_: Exception) {}
            }
        }
        entries.values.forEach { entry ->
            try { entry.classLoader.close() } catch (_: Exception) {}
        }
        entries.clear()
    }

    @Synchronized
    override fun getAllAddons(): List<AddonInfo> = entries.values.map { it.toAddonInfo() }

    @Synchronized
    override fun getAddon(id: String): AddonInfo? = entries[id]?.toAddonInfo()

    @Synchronized
    override fun getErrors(id: String): List<AddonError> = errors[id] ?: emptyList()

    private fun LoadedEntry.toAddonInfo(): AddonInfo = AddonInfo(
        id = manifest.id,
        name = manifest.name,
        version = manifest.version,
        apiVersion = manifest.apiVersion,
        state = state,
        connectorIds = if (state == AddonState.ENABLED) {
            context?.connectors?.map { it.config.id } ?: emptyList()
        } else {
            emptyList()
        },
        depends = manifest.depends,
        optionalDepends = manifest.optionalDepends,
        errors = errors[manifest.id] ?: emptyList()
    )

    private fun topoSort(
        ids: Set<String>,
        dependsOn: Map<String, List<String>>
    ): Pair<List<String>, Set<String>> {
        val adjList = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()

        for (id in ids) {
            inDegree[id] = 0
            adjList.getOrPut(id) { mutableListOf() }
        }

        for (id in ids) {
            for (dep in dependsOn[id] ?: emptyList()) {
                inDegree[id] = inDegree[id]!! + 1
                adjList.getOrPut(dep) { mutableListOf() }.add(id)
            }
        }

        val queue = ArrayDeque<String>()
        for ((id, degree) in inDegree) {
            if (degree == 0) queue.add(id)
        }

        val sorted = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            sorted.add(id)
            for (dependent in adjList[id] ?: emptyList()) {
                val newDegree = inDegree[dependent]!! - 1
                inDegree[dependent] = newDegree
                if (newDegree == 0) queue.add(dependent)
            }
        }

        val cyclic = ids - sorted.toSet()
        return Pair(sorted, cyclic)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseManifest(inputStream: InputStream): AddonManifest {
        val raw = Yaml().load<Map<String, Any>>(inputStream) ?: emptyMap()

        val id = raw["id"]?.toString()
            ?: throw IllegalArgumentException("addon.yml missing required field: id")
        if (!ID_PATTERN.matches(id)) {
            throw IllegalArgumentException(
                "addon.yml field 'id' must match ^[a-z][a-z0-9-]*\$ but was: '$id'"
            )
        }

        val apiVersion = raw["apiVersion"]?.toString()?.toIntOrNull()
            ?: throw IllegalArgumentException("addon.yml missing required field: apiVersion")
        val main = raw["main"]?.toString()
            ?: throw IllegalArgumentException("addon.yml missing required field: main")

        val depends = (raw["depends"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val optionalDepends = (raw["optionalDepends"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        return AddonManifest(
            id = id,
            name = raw["name"]?.toString() ?: "Unknown",
            version = raw["version"]?.toString() ?: "unknown",
            description = raw["description"]?.toString(),
            author = raw["author"]?.toString(),
            apiVersion = apiVersion,
            main = main,
            depends = depends,
            optionalDepends = optionalDepends
        )
    }
}

private val ID_PATTERN = Regex("^[a-z][a-z0-9-]*$")
