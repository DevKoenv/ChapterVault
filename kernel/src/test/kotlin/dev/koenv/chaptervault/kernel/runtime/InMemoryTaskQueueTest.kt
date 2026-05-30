package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

class InMemoryTaskQueueTest {
    private val queue = InMemoryTaskQueue()

    @Test
    fun `enqueue returns Success with the task ID`() {
        runTest {
            val t = task()
            val result = queue.enqueue(t)
            val success = assertIs<Result.Success<Id>>(result)
            assertEquals(t.id, success.value)
        }
    }

    @Test
    fun `dequeue returns the enqueued task`() {
        runTest {
            val t = task()
            queue.enqueue(t)
            val dequeued = queue.dequeue()
            assertEquals(t, dequeued)
        }
    }

    @Test
    fun `dequeue returns null when queue is empty`() {
        runTest {
            assertNull(queue.dequeue())
        }
    }

    @Test
    fun `enqueue then dequeue round-trips the same task`() {
        runTest {
            val t = task()
            queue.enqueue(t)
            val dequeued = queue.dequeue()
            assertEquals(t.id, dequeued?.id)
            assertEquals(t.type, dequeued?.type)
            assertEquals(t.status, dequeued?.status)
        }
    }

    @Test
    fun `cancel changes task status to CANCELLED`() {
        runTest {
            val t = task()
            queue.enqueue(t)
            queue.cancel(t.id)
            val stored = queue.getTask(t.id)
            assertNotNull(stored)
            assertEquals(TaskStatus.CANCELLED, stored.status)
        }
    }

    @Test
    fun `cancel returns NotFound for unknown task ID`() {
        runTest {
            val result = queue.cancel(Id.generate())
            val failure = assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>(failure.error)
        }
    }

    @Test
    fun `getTask returns task by ID after enqueue`() {
        runTest {
            val t = task()
            queue.enqueue(t)
            val found = queue.getTask(t.id)
            assertEquals(t, found)
        }
    }

    @Test
    fun `getTask returns null for unknown ID`() {
        runTest {
            assertNull(queue.getTask(Id.generate()))
        }
    }

    @Test
    fun `multiple tasks enqueued are dequeued in FIFO order`() {
        runTest {
            val first = task()
            val second = task()
            val third = task()
            queue.enqueue(first)
            queue.enqueue(second)
            queue.enqueue(third)

            assertEquals(first.id, queue.dequeue()?.id)
            assertEquals(second.id, queue.dequeue()?.id)
            assertEquals(third.id, queue.dequeue()?.id)
        }
    }
}
