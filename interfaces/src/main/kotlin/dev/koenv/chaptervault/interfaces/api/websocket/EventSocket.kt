package dev.koenv.chaptervault.interfaces.api.websocket

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close

fun Application.eventSocket(projectionService: EventProjectionService) {
    routing {
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
}
