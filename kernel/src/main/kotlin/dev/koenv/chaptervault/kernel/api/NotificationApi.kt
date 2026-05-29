package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface NotificationApi {
    suspend fun listTargets(): List<NotificationTarget>

    suspend fun findTarget(id: Id): Result<NotificationTarget>

    suspend fun createTarget(input: NotificationTargetInput): Result<NotificationTarget>

    suspend fun updateTarget(
        id: Id,
        patch: NotificationTargetPatch,
    ): Result<NotificationTarget>

    suspend fun deleteTarget(id: Id): Result<Unit>
}
