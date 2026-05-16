package dev.chaptervault.extensions.connectors

import dev.chaptervault.kernel.library.Chapter
import dev.chaptervault.kernel.library.Series
import dev.chaptervault.shared.format.ChapterFormat
import dev.chaptervault.shared.paging.PageRequest
import dev.chaptervault.shared.paging.Pagination
import dev.chaptervault.shared.result.Result

interface Connector {
    val id: String
    val name: String

    suspend fun search(query: String, request: PageRequest): Result<Pagination<SeriesSearchResult>>
    suspend fun fetchSeries(externalId: String): Result<SeriesMetadata>
    suspend fun fetchChapters(externalId: String): Result<List<ChapterMetadata>>
    suspend fun download(chapter: Chapter, format: ChapterFormat): Result<DownloadResult>
}
