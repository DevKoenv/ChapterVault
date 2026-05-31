package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.ExtensionEntry
import kotlinx.serialization.Serializable

@Serializable
data class ExtensionDto(
    val id: String,
    val name: String,
    val version: String,
    val status: String,
    val source: String,
    val capabilities: List<String>,
    val errorMessage: String? = null,
)

fun ExtensionEntry.toDto() =
    ExtensionDto(
        id = extension.id,
        name = extension.name,
        version = extension.version,
        status = status.name,
        source = source.name,
        capabilities =
            extension
                .capabilities()
                .map { capability ->
                    when (capability) {
                        Capability.CanFetchSeries, Capability.CanDownloadChapters -> "connector"
                        Capability.CanEnrichMetadata -> "metadata_enricher"
                        Capability.CanSendNotifications -> "notification_channel"
                    }
                }.distinct(),
        errorMessage = errorMessage,
    )
