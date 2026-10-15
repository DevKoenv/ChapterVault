package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

/** Which tasks a list returns. A null filter matches every task; filters combine with AND. */
data class TaskQuery(
    val status: TaskStatus? = null,
    val type: TaskType? = null,
    val targetId: Id? = null,
)

interface TaskReadStore {
    /** Newest first; tasks created at the same moment are ordered by id, so pages are stable. */
    suspend fun listTasks(
        query: TaskQuery,
        request: PageRequest,
    ): Result<Pagination<Task>>

    suspend fun listTasks(request: PageRequest): Result<Pagination<Task>> = listTasks(TaskQuery(), request)

    suspend fun findTask(id: Id): Result<Task>
}
