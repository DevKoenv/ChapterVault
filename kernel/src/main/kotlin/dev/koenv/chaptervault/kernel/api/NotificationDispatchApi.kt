package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface NotificationDispatchApi {
    suspend fun sendTest(targetId: Id): Result<Unit>
}
