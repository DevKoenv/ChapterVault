package dev.koenv.chaptervault.extensions.metadata

import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.result.Result

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
