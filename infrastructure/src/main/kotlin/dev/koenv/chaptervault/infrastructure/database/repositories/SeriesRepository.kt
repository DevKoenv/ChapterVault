package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

class SeriesRepository : LibraryReadApi, LibraryCommandApi {
    override suspend fun getSeries(id: Id): Result<Series> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))

    override suspend fun listSeries(request: PageRequest): Result<Pagination<Series>> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))

    override suspend fun searchLibrary(query: String, request: PageRequest): Result<Pagination<Series>> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))

    override suspend fun getChapter(id: Id): Result<Chapter> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))

    override suspend fun listChapters(seriesId: Id): Result<List<Chapter>> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))

    override suspend fun addToLibrary(connectorId: String, externalId: String, autoDownload: Boolean): Result<Series> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))

    override suspend fun removeSeries(id: Id): Result<Unit> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))

    override suspend fun updateSeries(id: Id, autoDownload: Boolean?, defaultFormat: ChapterFormat?): Result<Series> =
        Result.Failure(AppError.InternalError("SeriesRepository not yet implemented"))
}
