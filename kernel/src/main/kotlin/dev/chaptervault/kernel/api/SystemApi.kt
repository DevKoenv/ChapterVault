package dev.chaptervault.kernel.api

import dev.chaptervault.kernel.extension.Extension
import dev.chaptervault.kernel.runtime.Task
import dev.chaptervault.shared.paging.PageRequest
import dev.chaptervault.shared.paging.Pagination
import dev.chaptervault.shared.result.Result
import dev.chaptervault.shared.utils.Id

interface SystemApi {
    suspend fun listTasks(request: PageRequest): Result<Pagination<Task>>
    suspend fun getTask(id: Id): Result<Task>
    suspend fun cancelTask(id: Id): Result<Unit>
    fun listExtensions(): List<Extension>
    fun isHealthy(): Boolean
}
