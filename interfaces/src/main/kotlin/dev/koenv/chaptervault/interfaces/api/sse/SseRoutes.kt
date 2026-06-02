package dev.koenv.chaptervault.interfaces.api.sse

import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import java.io.IOException

fun Route.sseRoutes(projectionService: EventProjectionService) {
    sse("/events") {
        try {
            projectionService.events.collect { event ->
                send(event)
            }
        } catch (_: IOException) {
            // Client disconnected; stop sending silently.
        }
    }
}
