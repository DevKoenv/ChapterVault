package dev.koenv.chaptervault.kernel.extension

data class ExtensionEntry(
    val extension: Extension,
    val status: ExtensionStatus,
    val source: ExtensionSource,
    val errorMessage: String? = null,
    val registeredConnectorIds: List<String> = emptyList(),
)
