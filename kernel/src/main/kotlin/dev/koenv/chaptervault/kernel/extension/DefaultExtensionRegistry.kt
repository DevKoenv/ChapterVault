package dev.koenv.chaptervault.kernel.extension

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DefaultExtensionRegistry : ExtensionRegistry {
    private val extensions = ConcurrentHashMap<String, Extension>()
    private val capabilityIndex = ConcurrentHashMap<Capability, CopyOnWriteArrayList<Extension>>()

    override fun register(extension: Extension) {
        extensions[extension.id] = extension
        extension.capabilities.forEach { cap ->
            capabilityIndex.getOrPut(cap) { CopyOnWriteArrayList() }.add(extension)
        }
    }

    override fun all(): List<Extension> = extensions.values.toList()

    override fun withCapability(capability: Capability): List<Extension> = capabilityIndex[capability]?.toList() ?: emptyList()

    override fun findById(id: String): Extension? = extensions[id]
}
