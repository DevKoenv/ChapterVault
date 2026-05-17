package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.SystemApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.taskRoutes(system: SystemApi) {
    get("/tasks") {
        call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
    }
    post("/tasks/{id}/cancel") {
        call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
    }
}
