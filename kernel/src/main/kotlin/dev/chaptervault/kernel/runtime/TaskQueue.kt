package dev.chaptervault.kernel.runtime

import dev.chaptervault.shared.result.Result
import dev.chaptervault.shared.utils.Id

interface TaskQueue {
    suspend fun enqueue(task: Task): Result<Id>
    suspend fun dequeue(): Task?
    suspend fun cancel(taskId: Id): Result<Unit>
    suspend fun getTask(taskId: Id): Task?
}
