package dev.chaptervault.kernel.api

import dev.chaptervault.kernel.library.Series
import dev.chaptervault.shared.format.ChapterFormat
import dev.chaptervault.shared.result.Result
import dev.chaptervault.shared.utils.Id

interface LibraryCommandApi {
    suspend fun addToLibrary(connectorId: String, externalId: String, autoDownload: Boolean = false): Result<Series>
    suspend fun removeSeries(id: Id): Result<Unit>
    suspend fun updateSeries(id: Id, autoDownload: Boolean? = null, defaultFormat: ChapterFormat? = null): Result<Series>
}
