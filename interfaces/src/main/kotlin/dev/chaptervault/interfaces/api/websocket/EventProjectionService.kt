package dev.chaptervault.interfaces.api.websocket

import dev.chaptervault.kernel.event.DomainEvent
import dev.chaptervault.kernel.event.EventBus
import io.ktor.server.websocket.WebSocketServerSession
import kotlinx.coroutines.CoroutineScope

class EventProjectionService(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val sessions = mutableListOf<WebSocketServerSession>()

    fun addSession(session: WebSocketServerSession) {
        sessions.add(session)
    }

    fun removeSession(session: WebSocketServerSession) {
        sessions.remove(session)
    }

    suspend fun start() {
        eventBus.subscribe { event: DomainEvent ->
            // TODO: filter and fan-out events to connected sessions
        }
    }
}
