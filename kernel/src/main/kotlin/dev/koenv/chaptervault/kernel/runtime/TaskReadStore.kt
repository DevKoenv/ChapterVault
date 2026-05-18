package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface TaskReadStore {
    suspend fun listTasks(request: PageRequest): Result<Pagination<Task>>
    suspend fun findTask(id: Id): Result<Task>
}
