package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.kernel.event.DomainEvent
import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

sealed class TaskEvents : DomainEvent() {
    abstract val taskId: Id
    abstract val taskType: TaskType
    abstract val targetId: Id
    abstract val occurredAt: Instant

    data class TaskEnqueued(override val taskId: Id, override val taskType: TaskType, override val targetId: Id, override val occurredAt: Instant) : TaskEvents()
    data class TaskStarted(override val taskId: Id, override val taskType: TaskType, override val targetId: Id, override val occurredAt: Instant) : TaskEvents()
    data class TaskCompleted(override val taskId: Id, override val taskType: TaskType, override val targetId: Id, override val occurredAt: Instant) : TaskEvents()
    data class TaskFailed(override val taskId: Id, override val taskType: TaskType, override val targetId: Id, val errorMessage: String, override val occurredAt: Instant) : TaskEvents()
    data class TaskCancelled(override val taskId: Id, override val taskType: TaskType, override val targetId: Id, override val occurredAt: Instant) : TaskEvents()
}
