package dev.chaptervault.extensions.metadata

import dev.chaptervault.kernel.library.Series
import dev.chaptervault.shared.result.AppError
import dev.chaptervault.shared.result.Result

class AniListProvider : MetadataProvider {
    override val id: String = "anilist"

    override suspend fun enrich(series: Series): Result<SeriesMetadataEnrichment> =
        Result.Failure(AppError.InternalError("AniListProvider not yet implemented"))
}
