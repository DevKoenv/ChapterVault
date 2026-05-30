package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.kernel.event.DomainEvent
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun task(id: Id = Id.generate()) =
    Task(
        id = id,
        type = TaskType.FETCH_CHAPTERS,
        status = TaskStatus.PENDING,
        targetType = TargetType.SERIES,
        targetId = Id.generate(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

private class CapturingEventBus : EventBus {
    val published = mutableListOf<DomainEvent>()

    override suspend fun publish(event: DomainEvent) {
        published.add(event)
    }

    override fun subscribe(handler: suspend (DomainEvent) -> Unit) {}

    override fun <T : DomainEvent> subscribe(
        eventClass: Class<T>,
        handler: suspend (T) -> Unit,
    ) {}
}

class EventPublishingTaskQueueTest {
    private val inner = InMemoryTaskQueue()
    private val events = CapturingEventBus()
    private val queue = EventPublishingTaskQueue(inner, events)

    @Test
    fun `enqueue delegates to inner queue and returns its result`() {
        runTest {
            val t = task()
            val result = queue.enqueue(t)
            val success = assertIs<Result.Success<Id>>(result)
            assertEquals(t.id, success.value)
        }
    }

    @Test
    fun `enqueue publishes TaskEnqueued event on success`() {
        runTest {
            val t = task()
            queue.enqueue(t)

            assertEquals(1, events.published.size)
            val event = assertIs<TaskEvents.TaskEnqueued>(events.published[0])
            assertEquals(t.id, event.taskId)
            assertEquals(t.type, event.taskType)
        }
    }

    @Test
    fun `enqueue does NOT publish event when inner queue returns failure`() {
        runTest {
            val failingInner =
                object : TaskQueue {
                    override suspend fun enqueue(task: Task) =
                        Result.Failure(
                            dev.koenv.chaptervault.shared.result.AppError
                                .InternalError("forced failure"),
                        )

                    override suspend fun dequeue(): Task? = null

                    override suspend fun cancel(taskId: Id) = Result.Success(Unit)

                    override suspend fun getTask(taskId: Id): Task? = null
                }
            val q = EventPublishingTaskQueue(failingInner, events)

            q.enqueue(task())

            assertTrue(events.published.isEmpty())
        }
    }

    @Test
    fun `dequeue delegates to inner queue`() {
        runTest {
            val t = task()
            inner.enqueue(t)
            val dequeued = queue.dequeue()
            assertEquals(t.id, dequeued?.id)
        }
    }

    @Test
    fun `cancel delegates to inner queue`() {
        runTest {
            val t = task()
            inner.enqueue(t)
            val result = queue.cancel(t.id)
            assertIs<Result.Success<Unit>>(result)
            assertEquals(TaskStatus.CANCELLED, inner.getTask(t.id)?.status)
        }
    }

    @Test
    fun `getTask delegates to inner queue`() {
        runTest {
            val t = task()
            inner.enqueue(t)
            assertEquals(t, queue.getTask(t.id))
            assertNull(queue.getTask(Id.generate()))
        }
    }
}
