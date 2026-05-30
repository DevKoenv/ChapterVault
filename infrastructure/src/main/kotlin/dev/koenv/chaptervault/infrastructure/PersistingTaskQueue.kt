package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

// persists tasks to DB on enqueue/cancel so recoverOnBoot can replay them after a crash
class PersistingTaskQueue(
    private val delegate: TaskQueue,
    private val taskRepository: TaskRepository,
) : TaskQueue {
    override suspend fun enqueue(task: Task): Result<Id> {
        // insert is idempotent, safe to call for tasks already in DB (recover path)
        taskRepository.insert(task)
        return delegate.enqueue(task)
    }

    override suspend fun dequeue(): Task? = delegate.dequeue()

    override suspend fun cancel(taskId: Id): Result<Unit> {
        val result = delegate.cancel(taskId)
        if (result is Result.Success) taskRepository.updateStatus(taskId, TaskStatus.CANCELLED)
        return result
    }

    override suspend fun getTask(taskId: Id): Task? = delegate.getTask(taskId)
}
