package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result

class MangaDexConnector : Connector {
    override val id: String = "mangadex"
    override val name: String = "MangaDex"

    override suspend fun search(query: String, request: PageRequest): Result<Pagination<SeriesSearchResult>> =
        Result.Failure(AppError.InternalError("MangaDexConnector not yet implemented"))

    override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> =
        Result.Failure(AppError.InternalError("MangaDexConnector not yet implemented"))

    override suspend fun fetchChapters(externalId: String): Result<List<ChapterMetadata>> =
        Result.Failure(AppError.InternalError("MangaDexConnector not yet implemented"))

    override suspend fun download(chapter: Chapter, format: ChapterFormat): Result<DownloadResult> =
        Result.Failure(AppError.InternalError("MangaDexConnector not yet implemented"))
}
