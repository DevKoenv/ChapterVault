package dev.koenv.chaptervault.kernel.api.impl

import dev.koenv.chaptervault.kernel.event.DomainEvent
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionEntry
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionSource
import dev.koenv.chaptervault.kernel.extension.ExtensionStatus
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskEvents
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskReadStore
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

private class FakeTaskReadStore(
    private val tasks: List<Task> = emptyList(),
) : TaskReadStore {
    override suspend fun listTasks(request: PageRequest): Result<Pagination<Task>> =
        Result.Success(Pagination(tasks, request.page, request.size, tasks.size.toLong()))

    override suspend fun findTask(id: Id): Result<Task> =
        tasks
            .find { it.id == id }
            ?.let { Result.Success(it) }
            ?: Result.Failure(AppError.NotFound("Task", id.toString()))
}

private class FakeExtension(
    override val id: String,
    override val name: String = "Fake",
    override val version: String = "1.0.0",
) : Extension {
    override fun capabilities(): Set<Capability> = emptySet()

    override fun onEnable(context: ExtensionContext) {}

    override fun onDisable() {}
}

private fun fakeEntry(id: String) =
    ExtensionEntry(
        extension = FakeExtension(id),
        status = ExtensionStatus.ENABLED,
        source = ExtensionSource.BUNDLED,
    )

private class FakeExtensionRegistry(
    private val entries: List<ExtensionEntry> = emptyList(),
) : ExtensionRegistry {
    override fun register(entry: ExtensionEntry) {}

    override fun updateStatus(
        id: String,
        status: ExtensionStatus,
        errorMessage: String?,
    ) {}

    override fun unregister(id: String) {}

    override fun all(): List<ExtensionEntry> = entries

    override fun findById(id: String): ExtensionEntry? = entries.find { it.extension.id == id }

    override fun enabledWithCapability(capability: Capability): List<ExtensionEntry> = emptyList()
}

class SystemApiImplTest {
    private val taskQueue = InMemoryTaskQueue()
    private val events = CapturingEventBus()

    private fun api(
        queue: TaskQueue = taskQueue,
        registry: ExtensionRegistry = FakeExtensionRegistry(),
        store: TaskReadStore = FakeTaskReadStore(),
        bus: EventBus = events,
    ) = SystemApiImpl(queue, registry, store, bus)

    @Test
    fun `cancelTask cancels via queue and publishes TaskCancelled event on success`() {
        runTest {
            val t = task()
            taskQueue.enqueue(t)

            val result = api().cancelTask(t.id)

            assertIs<Result.Success<Unit>>(result)
            assertEquals(1, events.published.size)
            val event = assertIs<TaskEvents.TaskCancelled>(events.published[0])
            assertEquals(t.id, event.taskId)
        }
    }

    @Test
    fun `cancelTask does NOT publish event when cancel returns failure`() {
        runTest {
            val t = task()
            taskQueue.enqueue(t)
            val failingQueue =
                object : TaskQueue {
                    override suspend fun enqueue(task: Task) = Result.Success(task.id)

                    override suspend fun dequeue(): Task? = null

                    override suspend fun cancel(taskId: Id) = Result.Failure(AppError.NotFound("Task", taskId.toString()))

                    override suspend fun getTask(taskId: Id): Task = t
                }

            api(queue = failingQueue).cancelTask(t.id)

            assertTrue(events.published.isEmpty())
        }
    }

    @Test
    fun `cancelTask returns failure from queue unchanged`() {
        runTest {
            val result = api().cancelTask(Id.generate())
            val failure = assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>(failure.error)
        }
    }

    @Test
    fun `listExtensions delegates to registry all()`() {
        val entry = fakeEntry("ext-1")
        val result = api(registry = FakeExtensionRegistry(listOf(entry))).listExtensions()
        assertEquals(listOf(entry), result)
    }

    @Test
    fun `listTasks delegates to taskReadStore`() {
        runTest {
            val t = task()
            val store = FakeTaskReadStore(listOf(t))
            val result = api(store = store).listTasks(PageRequest())
            val success = assertIs<Result.Success<Pagination<Task>>>(result)
            assertEquals(listOf(t), success.value.items)
        }
    }

    @Test
    fun `getTask delegates to taskReadStore`() {
        runTest {
            val t = task()
            val store = FakeTaskReadStore(listOf(t))
            val result = api(store = store).getTask(t.id)
            val success = assertIs<Result.Success<Task>>(result)
            assertEquals(t, success.value)
        }
    }
}
