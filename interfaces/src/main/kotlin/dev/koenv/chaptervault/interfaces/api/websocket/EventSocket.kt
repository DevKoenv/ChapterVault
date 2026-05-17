package dev.koenv.chaptervault.interfaces.api.websocket

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close

fun Route.eventSocket(projectionService: EventProjectionService) {
    webSocket("/events") {
        projectionService.addSession(this)
        try {
            // TODO: handle incoming messages and stream events
            for (frame in incoming) { /* handle */ }
        } finally {
            projectionService.removeSession(this)
            close()
        }
    }
}
