package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.UpstreamStatus
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface LibraryCommandApi {
    suspend fun addToLibrary(
        connectorId: String,
        externalId: String,
        language: String = "en",
        autoDownload: Boolean = false,
    ): Result<Series>

    suspend fun removeSeries(id: Id): Result<Unit>

    suspend fun evictChapter(id: Id): Result<Unit>

    suspend fun markChapterPending(id: Id): Result<Unit>

    suspend fun updateSeries(
        id: Id,
        autoDownload: Boolean? = null,
        defaultFormat: ChapterFormat? = null,
        title: String? = null,
        coverUrl: String? = null,
        description: String? = null,
        author: String? = null,
        artist: String? = null,
        year: Int? = null,
        upstreamStatus: UpstreamStatus? = null,
        genres: List<String>? = null,
        clearFields: Set<String> = emptySet(),
    ): Result<Series>
}
