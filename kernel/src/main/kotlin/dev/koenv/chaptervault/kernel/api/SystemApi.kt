package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface SystemApi {
    suspend fun listTasks(request: PageRequest): Result<Pagination<Task>>
    suspend fun getTask(id: Id): Result<Task>
    suspend fun cancelTask(id: Id): Result<Unit>
    fun listExtensions(): List<Extension>
    fun isHealthy(): Boolean
}
