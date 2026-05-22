package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

class EventPublishingTaskQueue(
    private val delegate: TaskQueue,
    private val eventBus: EventBus,
) : TaskQueue {
    override suspend fun enqueue(task: Task): Result<Id> {
        val result = delegate.enqueue(task)
        if (result is Result.Success) {
            eventBus.publish(TaskEvents.TaskEnqueued(task.id, task.type, task.targetId, Instant.now()))
        }
        return result
    }

    override suspend fun dequeue(): Task? = delegate.dequeue()

    override suspend fun cancel(taskId: Id): Result<Unit> = delegate.cancel(taskId)

    override suspend fun getTask(taskId: Id): Task? = delegate.getTask(taskId)
}
