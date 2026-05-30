package dev.koenv.chaptervault.kernel.extension

interface ConnectorRegistrar {
    fun registerRaw(
        id: String,
        connector: Any,
    )

    fun unregister(id: String)
}
