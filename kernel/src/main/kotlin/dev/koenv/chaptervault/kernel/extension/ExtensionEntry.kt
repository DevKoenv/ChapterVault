package dev.koenv.chaptervault.kernel.extension

import java.nio.file.Path

data class ExtensionEntry(
    val extension: Extension,
    val status: ExtensionStatus,
    val source: ExtensionSource,
    val errorMessage: String? = null,
    val jarPath: Path? = null,
    val registeredConnectorIds: List<String> = emptyList(),
    val registeredEnricherIds: List<String> = emptyList(),
    val registeredChannelTypeIds: List<String> = emptyList(),
)
