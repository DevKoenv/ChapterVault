package dev.koenv.chaptervault.interfaces.api.sse

import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import java.io.IOException

fun Route.sseRoutes(projectionService: EventProjectionService) {
    sse("/events") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null) {
            call.response.status(HttpStatusCode.Unauthorized)
            return@sse
        }
        try {
            // only the events this user is allowed to see; task events carry upstream URLs and error text
            projectionService.eventsFor(principal.user).collect { event ->
                send(event)
            }
        } catch (_: IOException) {
            // Client disconnected; stop sending silently.
        }
    }
}
