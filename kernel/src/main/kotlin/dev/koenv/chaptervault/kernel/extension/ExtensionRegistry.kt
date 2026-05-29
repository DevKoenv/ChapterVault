package dev.koenv.chaptervault.kernel.extension

interface ExtensionRegistry {
    fun register(extension: Extension)

    fun all(): List<Extension>

    fun withCapability(capability: Capability): List<Extension>

    fun findById(id: String): Extension?
}
