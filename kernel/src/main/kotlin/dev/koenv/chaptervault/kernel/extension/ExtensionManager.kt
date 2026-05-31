package dev.koenv.chaptervault.kernel.extension

interface ExtensionManager {
    fun listAll(): List<ExtensionEntry>
    fun findById(id: String): ExtensionEntry?
    fun enable(id: String)
    fun disable(id: String)
}
