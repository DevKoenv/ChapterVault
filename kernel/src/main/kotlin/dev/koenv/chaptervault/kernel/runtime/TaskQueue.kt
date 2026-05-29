package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface TaskQueue {
    suspend fun enqueue(task: Task): Result<Id>

    suspend fun dequeue(): Task?

    suspend fun cancel(taskId: Id): Result<Unit>

    suspend fun getTask(taskId: Id): Task?
}
