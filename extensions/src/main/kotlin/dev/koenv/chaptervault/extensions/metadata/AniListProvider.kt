package dev.koenv.chaptervault.extensions.metadata

import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result

class AniListProvider : MetadataProvider {
    override val id: String = "anilist"

    override suspend fun enrich(series: Series): Result<SeriesMetadataEnrichment> =
        Result.Failure(AppError.InternalError("AniListProvider not yet implemented"))
}
