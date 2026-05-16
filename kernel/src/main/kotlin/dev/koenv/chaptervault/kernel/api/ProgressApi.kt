package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

data class ReadProgress(val seriesId: Id, val readCount: Int, val totalCount: Int)

interface ProgressApi {
    suspend fun markRead(chapterId: Id): Result<Unit>
    suspend fun markUnread(chapterId: Id): Result<Unit>
    suspend fun getProgress(seriesId: Id): Result<ReadProgress>
}
