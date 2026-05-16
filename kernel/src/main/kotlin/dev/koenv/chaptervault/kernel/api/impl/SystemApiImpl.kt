package dev.koenv.chaptervault.kernel.api.impl

import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

class SystemApiImpl(
    private val taskQueue: TaskQueue,
    private val registry: ExtensionRegistry,
) : SystemApi {
    override suspend fun listTasks(request: PageRequest): Result<Pagination<Task>> =
        Result.Success(Pagination(emptyList(), request.page, request.size, 0L))

    override suspend fun getTask(id: Id): Result<Task> =
        taskQueue.getTask(id)?.let { Result.Success(it) }
            ?: Result.Failure(AppError.NotFound("Task", id.toString()))

    override suspend fun cancelTask(id: Id): Result<Unit> = taskQueue.cancel(id)

    override fun listExtensions(): List<Extension> = registry.all()

    override fun isHealthy(): Boolean = true
}
