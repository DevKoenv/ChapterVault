package dev.koenv.chaptervault.kernel.extension

interface Extension {
    val id: String
    val name: String
    val version: String
    val capabilities: Set<Capability> get() = emptySet()
}
