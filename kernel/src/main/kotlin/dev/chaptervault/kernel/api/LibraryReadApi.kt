package dev.chaptervault.kernel.api

import dev.chaptervault.kernel.library.Chapter
import dev.chaptervault.kernel.library.Series
import dev.chaptervault.shared.paging.PageRequest
import dev.chaptervault.shared.paging.Pagination
import dev.chaptervault.shared.result.Result
import dev.chaptervault.shared.utils.Id

interface LibraryReadApi {
    suspend fun getSeries(id: Id): Result<Series>
    suspend fun listSeries(request: PageRequest): Result<Pagination<Series>>
    suspend fun searchLibrary(query: String, request: PageRequest): Result<Pagination<Series>>
    suspend fun getChapter(id: Id): Result<Chapter>
    suspend fun listChapters(seriesId: Id): Result<List<Chapter>>
}
