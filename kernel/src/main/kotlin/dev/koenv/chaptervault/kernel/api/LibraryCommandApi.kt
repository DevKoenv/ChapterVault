package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface LibraryCommandApi {
    suspend fun addToLibrary(connectorId: String, externalId: String, language: String = "", autoDownload: Boolean = false): Result<Series>
    suspend fun removeSeries(id: Id): Result<Unit>
    suspend fun updateSeries(id: Id, autoDownload: Boolean? = null, defaultFormat: ChapterFormat? = null): Result<Series>
}
