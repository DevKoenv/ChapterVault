package dev.koenv.chaptervault.interfaces.api.sse

import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.library.ChapterEvents
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.runtime.TaskEvents
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EventProjectionServiceTest {
    private val taskId = Id.from("00000000-0000-0000-0000-000000000001")
    private val seriesId = Id.from("00000000-0000-0000-0000-000000000002")
    private val chapterId = Id.from("00000000-0000-0000-0000-000000000003")
    private val now = Instant.EPOCH

    private suspend fun CoroutineScope.collectOne(service: EventProjectionService, publish: suspend () -> Unit): ServerSentEvent {
        val collected = mutableListOf<ServerSentEvent>()
        val job = launch { service.events.take(1).toList(collected) }
        yield() // let collector register before emitting
        publish()
        job.join()
        return collected.single()
    }

    @Test
    fun `TaskEnqueued emits task state_changed with QUEUED status`() = runBlocking {
        val eventBus = InMemoryEventBus()
        val service = EventProjectionService(eventBus)
        service.start()

        val event = collectOne(service) {
            eventBus.publish(TaskEvents.TaskEnqueued(taskId, TaskType.FETCH_SERIES_METADATA, seriesId, now))
        }

        assertEquals("task.state_changed", event.event)
        assertContains(event.data!!, "QUEUED")
        assertContains(event.data!!, taskId.toString())
        assertContains(event.data!!, seriesId.toString())
    }

    @Test
    fun `TaskStarted emits task state_changed with RUNNING status`() = runBlocking {
        val eventBus = InMemoryEventBus()
        val service = EventProjectionService(eventBus)
        service.start()

        val event = collectOne(service) {
            eventBus.publish(TaskEvents.TaskStarted(taskId, TaskType.DOWNLOAD_CHAPTER, seriesId, now))
        }

        assertEquals("task.state_changed", event.event)
        assertContains(event.data!!, "RUNNING")
    }

    @Test
    fun `TaskCompleted emits task state_changed with COMPLETED status`() = runBlocking {
        val eventBus = InMemoryEventBus()
        val service = EventProjectionService(eventBus)
        service.start()

        val event = collectOne(service) {
            eventBus.publish(TaskEvents.TaskCompleted(taskId, TaskType.DOWNLOAD_CHAPTER, seriesId, now))
        }

        assertEquals("task.state_changed", event.event)
        assertContains(event.data!!, "COMPLETED")
    }

    @Test
    fun `TaskFailed emits task state_changed with FAILED status and error message`() = runBlocking {
        val eventBus = InMemoryEventBus()
        val service = EventProjectionService(eventBus)
        service.start()

        val event = collectOne(service) {
            eventBus.publish(TaskEvents.TaskFailed(taskId, TaskType.DOWNLOAD_CHAPTER, seriesId, "network error", now))
        }

        assertEquals("task.state_changed", event.event)
        assertContains(event.data!!, "FAILED")
        assertContains(event.data!!, "network error")
    }

    @Test
    fun `TaskCancelled emits task state_changed with CANCELLED status`() = runBlocking {
        val eventBus = InMemoryEventBus()
        val service = EventProjectionService(eventBus)
        service.start()

        val event = collectOne(service) {
            eventBus.publish(TaskEvents.TaskCancelled(taskId, TaskType.DOWNLOAD_CHAPTER, seriesId, now))
        }

        assertEquals("task.state_changed", event.event)
        assertContains(event.data!!, "CANCELLED")
    }

    @Test
    fun `DownloadStatusChanged emits chapter download_status event`() = runBlocking {
        val eventBus = InMemoryEventBus()
        val service = EventProjectionService(eventBus)
        service.start()

        val event = collectOne(service) {
            eventBus.publish(ChapterEvents.DownloadStatusChanged(chapterId, seriesId, DownloadStatus.DOWNLOADED, now))
        }

        assertEquals("chapter.download_status", event.event)
        assertContains(event.data!!, chapterId.toString())
        assertContains(event.data!!, seriesId.toString())
        assertContains(event.data!!, "DOWNLOADED")
    }
}
