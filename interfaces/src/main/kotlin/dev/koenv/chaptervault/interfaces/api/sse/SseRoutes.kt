package dev.koenv.chaptervault.interfaces.api.sse

import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse

fun Route.sseRoutes(projectionService: EventProjectionService) {
    sse("/events") {
        projectionService.events.collect { event ->
            send(event)
        }
    }
}
