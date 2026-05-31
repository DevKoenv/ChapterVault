package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result

interface Connector {
    val id: String
    val name: String

    suspend fun search(query: String, request: PageRequest): Result<Pagination<SeriesSearchResult>>
    suspend fun fetchSeries(externalId: String): Result<SeriesMetadata>
    suspend fun fetchChapters(externalId: String, language: String = ""): Result<List<ChapterMetadata>>
    suspend fun download(chapter: Chapter, format: ChapterFormat): Result<DownloadResult>
    suspend fun fetchPage(page: DownloadPage): Result<ByteArray> =
        Result.Failure(AppError.InternalError("fetchPage not supported by connector '$id'"))

    fun supportedLanguages(): List<String> = listOf("en")
}
