package dev.chaptervault.extensions.metadata

import dev.chaptervault.kernel.library.Series
import dev.chaptervault.shared.result.Result

data class SeriesMetadataEnrichment(
    val description: String? = null,
    val coverUrl: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
)

interface MetadataProvider {
    val id: String
    suspend fun enrich(series: Series): Result<SeriesMetadataEnrichment>
}
