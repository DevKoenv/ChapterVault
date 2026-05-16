package dev.chaptervault.kernel.runtime

import dev.chaptervault.kernel.event.DomainEvent
import dev.chaptervault.shared.utils.Id
import java.time.Instant

sealed class TaskEvent : DomainEvent() {
    abstract val taskId: Id
    abstract val occurredAt: Instant

    data class TaskEnqueued(override val taskId: Id, val taskType: TaskType, override val occurredAt: Instant) : TaskEvent()
    data class TaskStarted(override val taskId: Id, override val occurredAt: Instant) : TaskEvent()
    data class TaskCompleted(override val taskId: Id, override val occurredAt: Instant) : TaskEvent()
    data class TaskFailed(override val taskId: Id, val errorMessage: String, override val occurredAt: Instant) : TaskEvent()
    data class TaskCancelled(override val taskId: Id, override val occurredAt: Instant) : TaskEvent()
}
