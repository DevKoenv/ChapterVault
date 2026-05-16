package dev.chaptervault.extensions.connectors

import dev.chaptervault.kernel.library.Chapter
import dev.chaptervault.shared.format.ChapterFormat
import dev.chaptervault.shared.paging.PageRequest
import dev.chaptervault.shared.paging.Pagination
import dev.chaptervault.shared.result.Result

class MockConnector : Connector {
    override val id: String = "mock"
    override val name: String = "Mock Connector"

    override suspend fun search(query: String, request: PageRequest): Result<Pagination<SeriesSearchResult>> =
        Result.Success(Pagination(emptyList(), request.page, request.size, 0L))

    override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> =
        Result.Success(SeriesMetadata(externalId = externalId, title = "Mock Series [$externalId]"))

    override suspend fun fetchChapters(externalId: String): Result<List<ChapterMetadata>> =
        Result.Success(emptyList())

    override suspend fun download(chapter: Chapter, format: ChapterFormat): Result<DownloadResult> =
        Result.Success(DownloadResult(pageUrls = emptyList(), totalPages = 0))
}
