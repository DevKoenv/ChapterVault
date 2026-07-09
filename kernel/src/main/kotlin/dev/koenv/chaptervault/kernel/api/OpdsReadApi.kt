package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class RecentlyReadEntry(
    val series: Series,
    val lastReadAt: Instant,
    val chaptersRead: Int,
    val totalChapters: Int,
)

data class ChapterWithSeries(
    val chapter: Chapter,
    val series: Series,
)

data class GenreCount(val genre: String, val seriesCount: Int)
data class LanguageCount(val language: String, val seriesCount: Int)
data class StatusCount(val status: String, val seriesCount: Int)

data class OpdsSeriesFilter(
    val genre: String? = null,
    val language: String? = null,
    val upstreamStatus: String? = null,
)

interface OpdsReadApi {
    suspend fun listReadHistory(userId: Id, request: PageRequest): Result<Pagination<RecentlyReadEntry>>
    suspend fun listRecentlyAddedChapters(request: PageRequest): Result<Pagination<ChapterWithSeries>>
    suspend fun listGenres(): Result<List<GenreCount>>
    suspend fun listLanguages(): Result<List<LanguageCount>>
    suspend fun listUpstreamStatuses(): Result<List<StatusCount>>
    suspend fun listSeriesFiltered(filter: OpdsSeriesFilter, request: PageRequest): Result<Pagination<Series>>
}
