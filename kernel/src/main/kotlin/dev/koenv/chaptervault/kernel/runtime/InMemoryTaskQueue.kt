package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

class InMemoryTaskQueue : TaskQueue {
    private val store = ConcurrentHashMap<Id, Task>()
    private val channel = Channel<Task>(Channel.UNLIMITED)

    override suspend fun enqueue(task: Task): Result<Id> {
        store[task.id] = task
        channel.send(task)
        return Result.Success(task.id)
    }

    override suspend fun dequeue(): Task? = channel.tryReceive().getOrNull()

    override suspend fun cancel(taskId: Id): Result<Unit> {
        val task =
            store[taskId]
                ?: return Result.Failure(AppError.NotFound("Task", taskId.toString()))
        store[taskId] = task.copy(status = TaskStatus.CANCELLED)
        return Result.Success(Unit)
    }

    override suspend fun getTask(taskId: Id): Task? = store[taskId]
}
