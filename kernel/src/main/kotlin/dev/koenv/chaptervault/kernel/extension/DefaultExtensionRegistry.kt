package dev.koenv.chaptervault.kernel.extension

import java.util.concurrent.ConcurrentHashMap

class DefaultExtensionRegistry : ExtensionRegistry {
    private val entries = ConcurrentHashMap<String, ExtensionEntry>()

    override fun register(entry: ExtensionEntry) {
        entries[entry.extension.id] = entry
    }

    override fun updateStatus(
        id: String,
        status: ExtensionStatus,
        errorMessage: String?,
    ) {
        entries.computeIfPresent(id) { _, entry ->
            entry.copy(status = status, errorMessage = errorMessage)
        }
    }

    override fun unregister(id: String) {
        entries.remove(id)
    }

    override fun all(): List<ExtensionEntry> = entries.values.toList()

    override fun findById(id: String): ExtensionEntry? = entries[id]

    override fun enabledWithCapability(capability: Capability): List<ExtensionEntry> =
        entries.values.filter { it.status == ExtensionStatus.ENABLED && capability in it.extension.capabilities() }
}
