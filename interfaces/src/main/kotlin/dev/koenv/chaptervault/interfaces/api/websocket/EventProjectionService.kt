package dev.koenv.chaptervault.interfaces.api.websocket

import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.library.ChapterEvents
import dev.koenv.chaptervault.kernel.runtime.TaskEvents
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An event for the stream, together with the permission a user needs to be allowed to see it. */
private class ProjectedEvent(
    val event: ServerSentEvent,
    val requires: Permission,
)

class EventProjectionService(
    private val eventBus: EventBus,
) {
    private val _events =
        MutableSharedFlow<ProjectedEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Every event, unfiltered. Streams for users must go through [eventsFor]. */
    val events: Flow<ServerSentEvent> = _events.asSharedFlow().map { it.event }

    /** The events [user] may see: task events need [Permission.VIEW_TASKS], library events [Permission.READ_LIBRARY]. */
    fun eventsFor(user: UserPrincipal): Flow<ServerSentEvent> =
        _events.asSharedFlow().filter { user.hasPermission(it.requires) }.map { it.event }

    suspend fun start() {
        eventBus.subscribe { event ->
            when (event) {
                is TaskEvents.TaskEnqueued -> emitTaskEvent(event, "QUEUED")
                is TaskEvents.TaskStarted -> emitTaskEvent(event, "RUNNING")
                is TaskEvents.TaskCompleted -> emitTaskEvent(event, "COMPLETED")
                is TaskEvents.TaskFailed -> emitTaskEvent(event, "FAILED", event.errorMessage)
                is TaskEvents.TaskCancelled -> emitTaskEvent(event, "CANCELLED")
                is ChapterEvents.DownloadStatusChanged -> {
                    val data =
                        buildJsonObject {
                            put("chapterId", event.chapterId.toString())
                            put("seriesId", event.seriesId.toString())
                            put("status", event.status.name)
                            put("occurredAt", event.occurredAt.toString())
                        }.toString()
                    _events.emit(ProjectedEvent(ServerSentEvent(data = data, event = "chapter.download_status"), Permission.READ_LIBRARY))
                }
                else -> Unit
            }
        }
    }

    private suspend fun emitTaskEvent(
        event: TaskEvents,
        status: String,
        errorMessage: String? = null,
    ) {
        val data =
            buildJsonObject {
                put("taskId", event.taskId.toString())
                put("taskType", event.taskType.name)
                put("targetId", event.targetId.toString())
                put("status", status)
                if (errorMessage != null) put("errorMessage", errorMessage)
                put("occurredAt", event.occurredAt.toString())
            }.toString()
        _events.emit(ProjectedEvent(ServerSentEvent(data = data, event = "task.state_changed"), Permission.VIEW_TASKS))
    }
}
