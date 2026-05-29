package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface LibraryReadApi {
    suspend fun getSeries(id: Id): Result<Series>

    suspend fun listSeries(request: PageRequest): Result<Pagination<Series>>

    suspend fun searchLibrary(
        query: String,
        request: PageRequest,
    ): Result<Pagination<Series>>

    suspend fun getChapter(id: Id): Result<Chapter>

    suspend fun listChapters(seriesId: Id): Result<List<Chapter>>

    suspend fun listChaptersByStatus(
        seriesId: Id,
        status: DownloadStatus,
    ): Result<List<Chapter>>

    suspend fun inLibraryExternalIds(
        connectorId: String,
        externalIds: List<String>,
    ): Result<Set<String>>
}
