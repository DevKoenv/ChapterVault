package dev.koenv.chaptervault.kernel.extension

interface ExtensionRegistry {
    fun register(entry: ExtensionEntry)

    fun updateStatus(
        id: String,
        status: ExtensionStatus,
        errorMessage: String? = null,
    )

    fun unregister(id: String)

    fun all(): List<ExtensionEntry>

    fun findById(id: String): ExtensionEntry?

    fun enabledWithCapability(capability: Capability): List<ExtensionEntry>
}
