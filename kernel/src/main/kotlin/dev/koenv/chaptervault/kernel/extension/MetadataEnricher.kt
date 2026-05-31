package dev.koenv.chaptervault.kernel.extension

import dev.koenv.chaptervault.kernel.library.UpstreamStatus
import dev.koenv.chaptervault.shared.result.Result

interface MetadataEnricher {
    val id: String
    suspend fun enrich(series: EnricherInput): Result<EnrichedMetadata>
}

data class EnricherInput(
    val externalId: String,
    val connectorId: String,
    val title: String,
    val coverUrl: String? = null,
    val description: String? = null,
)

data class EnrichedMetadata(
    val author: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val upstreamStatus: UpstreamStatus? = null,
    val genres: List<String> = emptyList(),
)
