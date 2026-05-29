package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface ReadingStatusApi {
    suspend fun setStatus(
        userId: Id,
        seriesId: Id,
        status: ReadingStatus,
    ): Result<Unit>

    suspend fun clearStatus(
        userId: Id,
        seriesId: Id,
    ): Result<Unit>

    suspend fun getStatus(
        userId: Id,
        seriesId: Id,
    ): ReadingStatus?
}
