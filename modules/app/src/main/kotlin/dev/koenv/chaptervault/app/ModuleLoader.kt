package dev.koenv.chaptervault.app

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.execution.Executor
import dev.koenv.chaptervault.core.module.ModuleEntrypoint
import dev.koenv.chaptervault.core.module.ModuleContext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.net.URLClassLoader

private val logger = KotlinLogging.logger {}

class ModuleLoader(private val modulesDir: File, private val executor: Executor) {

    companion object {
        const val CURRENT_API_VERSION = 1
        const val MINIMUM_SUPPORTED_VERSION = 1
    }

    private data class LoadedModule(
        val manifest: ModuleManifest,
        val module: ModuleEntrypoint,
        val context: ModuleContextImpl
    )

    private val loaded = mutableListOf<LoadedModule>()

    /**
     * Scans [modulesDir] for JAR files, reads each module.yml, validates the API version,
     * calls [ChapterVaultModule.onLoad] on every instantiated module, then calls
     * [ChapterVaultModule.onEnable] to collect registrations.
     *
     * Returns all connectors registered across all modules.
     * Call [shutdown] during server shutdown to invoke [ChapterVaultModule.onDisable].
     */
    fun load(): List<Connector> {
        if (!modulesDir.exists() || !modulesDir.isDirectory) return emptyList()

        val jars = modulesDir.listFiles { f -> f.extension == "jar" }
            ?.takeIf { it.isNotEmpty() } ?: return emptyList()

        // Phase 1: instantiate all modules and call onLoad
        for (jar in jars) {
            logger.info { "Loading module JAR: ${jar.name}" }
            try {
                val classLoader = URLClassLoader(
                    arrayOf(jar.toURI().toURL()),
                    Thread.currentThread().contextClassLoader
                )

                val manifestStream = classLoader.getResourceAsStream("module.yml")
                if (manifestStream == null) {
                    logger.error { "No module.yml found in ${jar.name} — skipping" }
                    continue
                }

                val manifest = manifestStream.use { ModuleManifest.parse(it) }

                if (manifest.apiVersion < MINIMUM_SUPPORTED_VERSION) {
                    logger.error {
                        "Module '${manifest.name}' in ${jar.name} reports apiVersion=${manifest.apiVersion}, " +
                            "minimum is $MINIMUM_SUPPORTED_VERSION — skipping"
                    }
                    continue
                }

                val module = classLoader.loadClass(manifest.main)
                    .getDeclaredConstructor()
                    .newInstance() as ModuleEntrypoint

                module.onLoad()
                logger.info { "Loaded module '${manifest.name}' v${manifest.version} (${jar.name})" }

                loaded += LoadedModule(manifest, module, ModuleContextImpl(executor))
            } catch (e: Exception) {
                logger.error(e) { "Failed to load module from ${jar.name}: ${e.message}" }
            }
        }

        // Phase 2: enable all successfully loaded modules
        val connectors = mutableListOf<Connector>()
        for ((manifest, module, context) in loaded) {
            try {
                module.onEnable(context)
                logger.info {
                    "Enabled module '${manifest.name}' v${manifest.version} - " +
                        "registered ${context.connectors.size} connector(s)"
                }
                connectors.addAll(context.connectors)
            } catch (e: Exception) {
                logger.error(e) { "Failed to enable module '${manifest.name}': ${e.message}" }
            }
        }

        return connectors
    }

    /**
     * Calls [ChapterVaultModule.onDisable] on all loaded modules in reverse load order.
     * Safe to call even if [load] was never called or returned early.
     */
    fun shutdown() {
        for ((manifest, module, _) in loaded.reversed()) {
            try {
                module.onDisable()
                logger.info { "Disabled module '${manifest.name}'" }
            } catch (e: Exception) {
                logger.error(e) { "Error disabling module '${manifest.name}': ${e.message}" }
            }
        }
        loaded.clear()
    }
}

private class ModuleContextImpl(override val executor: Executor) : ModuleContext {
    val connectors = mutableListOf<Connector>()
    override fun registerConnector(connector: Connector) {
        connectors += connector
    }
}
