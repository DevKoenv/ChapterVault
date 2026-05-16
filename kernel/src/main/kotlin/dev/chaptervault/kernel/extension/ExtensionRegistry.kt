package dev.chaptervault.kernel.extension

interface ExtensionRegistry {
    fun register(extension: Extension)
    fun all(): List<Extension>
    fun <C : Capability> withCapability(capability: C): List<Extension>
    fun findById(id: String): Extension?
}
