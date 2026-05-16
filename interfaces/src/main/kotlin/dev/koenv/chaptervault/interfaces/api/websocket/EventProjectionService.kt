package dev.koenv.chaptervault.interfaces.api.websocket

import dev.koenv.chaptervault.kernel.event.DomainEvent
import dev.koenv.chaptervault.kernel.event.EventBus
import io.ktor.server.websocket.WebSocketServerSession
import java.util.concurrent.CopyOnWriteArrayList

class EventProjectionService(
    private val eventBus: EventBus,
) {
    // CopyOnWriteArrayList: sessions are added/removed concurrently from WebSocket handlers
    private val sessions = CopyOnWriteArrayList<WebSocketServerSession>()

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
