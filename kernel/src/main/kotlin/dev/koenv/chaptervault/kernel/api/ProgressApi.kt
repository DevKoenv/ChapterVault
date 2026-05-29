package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

data class ReadProgress(
    val seriesId: Id,
    val readCount: Int,
    val totalCount: Int,
)

interface ProgressApi {
    suspend fun markRead(
        userId: Id,
        chapterId: Id,
    ): Result<Unit>

    suspend fun markUnread(
        userId: Id,
        chapterId: Id,
    ): Result<Unit>

    suspend fun getProgress(
        userId: Id,
        seriesId: Id,
    ): Result<ReadProgress>
}
