package dev.chaptervault.interfaces.api.rest

import dev.chaptervault.kernel.api.SystemApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.taskRoutes(system: SystemApi) {
    routing {
        get("/tasks") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
        post("/tasks/{id}/cancel") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
    }
}
