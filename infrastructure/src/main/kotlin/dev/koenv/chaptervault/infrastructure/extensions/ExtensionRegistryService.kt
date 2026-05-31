package dev.koenv.chaptervault.infrastructure.extensions

import dev.koenv.chaptervault.infrastructure.database.repositories.ExtensionRegistryRepository
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import org.slf4j.LoggerFactory

class ExtensionRegistryService(
    private val registryRepo: ExtensionRegistryRepository,
    private val registryClient: ExtensionRegistryClient,
    private val extensionManager: ExtensionManager,
    private val httpClient: HttpClient,
) {
    private val log = LoggerFactory.getLogger(ExtensionRegistryService::class.java)

    suspend fun listAll(): List<ResolvedCatalogEntry> {
        val registries = registryRepo.list().filter { it.enabled }
        val catalogs = registries.mapNotNull { registry ->
            runCatching { registryClient.fetch(registry.url) }
                .onFailure { log.warn("Failed to fetch registry '${registry.name}': ${it.message}") }
                .getOrNull()
        }
        return mergeCatalogs(catalogs)
    }

    suspend fun refresh() {
        registryRepo.list().forEach { registry ->
            registryClient.invalidate(registry.url)
        }
        listAll() // best-effort cache warm; fetch errors are logged inside listAll but not propagated
    }

    suspend fun install(extensionId: String) {
        val all = listAll()
        requireNonConflicting(extensionId, all.filter { it.entry.id == extensionId })
        val entry = all.firstOrNull { it.entry.id == extensionId }
            ?: error("Extension '$extensionId' not found in any registry")
        val bytes = httpClient.get(entry.entry.jarUrl).readRawBytes()
        extensionManager.install(extensionId, bytes)
        log.info("Installed extension '$extensionId' from ${entry.registryName}")
    }

    companion object {
        fun mergeCatalogs(catalogs: List<RegistryCatalog>): List<ResolvedCatalogEntry> {
            val seen = mutableMapOf<String, String>()
            val conflictingIds = mutableSetOf<String>()
            val result = mutableListOf<ResolvedCatalogEntry>()
            catalogs.forEach { catalog ->
                catalog.extensions.forEach { entry ->
                    val existing = seen[entry.id]
                    if (existing != null) conflictingIds.add(entry.id)
                    seen[entry.id] = catalog.registryName
                    result.add(ResolvedCatalogEntry(entry = entry, registryName = catalog.registryName, conflicting = false))
                }
            }
            return result.map { it.copy(conflicting = it.entry.id in conflictingIds) }
        }

        fun requireNonConflicting(extensionId: String, matches: List<ResolvedCatalogEntry>) {
            if (matches.any { it.conflicting }) {
                throw ConflictingExtensionException(extensionId, matches.map { it.registryName })
            }
        }
    }
}
