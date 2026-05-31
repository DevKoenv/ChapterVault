package dev.koenv.chaptervault.kernel.extension

interface Extension {
    val id: String
    val name: String
    val version: String

    fun capabilities(): Set<Capability>

    fun onEnable(context: ExtensionContext)

    fun onDisable()

    fun onConfigChanged(
        key: String,
        value: String,
    ) {}

    fun configFields(): List<ExtensionConfigField> = emptyList()
}
