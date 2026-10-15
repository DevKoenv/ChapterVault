package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

enum class SeriesSort(
    val defaultDescending: Boolean,
) {
    /** Title, ignoring case. */
    TITLE(false),

    /** When the series was added to the library, newest first by default. */
    ADDED(true),
}

/** Which series a list returns and in which order. A null filter matches every series; filters combine with AND. */
data class SeriesQuery(
    val sort: SeriesSort = SeriesSort.TITLE,
    val descending: Boolean = sort.defaultDescending,
    val ids: Collection<Id>? = null,
    val titleContains: String? = null,
    val genre: String? = null,
    val language: String? = null,
    val upstreamStatus: String? = null,
)

interface LibraryReadApi {
    suspend fun getSeries(id: Id): Result<Series>

    /** Pages are stable: ties in the sort key are broken by id. */
    suspend fun listSeries(
        query: SeriesQuery,
        request: PageRequest,
    ): Result<Pagination<Series>>

    suspend fun listSeries(request: PageRequest): Result<Pagination<Series>> = listSeries(SeriesQuery(), request)

    suspend fun listSeriesByIds(
        ids: Collection<Id>,
        request: PageRequest,
    ): Result<Pagination<Series>> = listSeries(SeriesQuery(ids = ids), request)

    suspend fun searchLibrary(
        query: String,
        request: PageRequest,
    ): Result<Pagination<Series>> = listSeries(SeriesQuery(titleContains = query), request)

    suspend fun getChapter(id: Id): Result<Chapter>

    suspend fun listChapters(seriesId: Id): Result<List<Chapter>>

    suspend fun listChaptersByStatus(
        seriesId: Id,
        status: DownloadStatus,
    ): Result<List<Chapter>>

    suspend fun listChaptersPaginated(
        seriesId: Id,
        status: DownloadStatus? = null,
        request: PageRequest,
    ): Result<Pagination<Chapter>>

    suspend fun inLibraryExternalIds(
        connectorId: String,
        externalIds: List<String>,
    ): Result<Set<String>>
}
