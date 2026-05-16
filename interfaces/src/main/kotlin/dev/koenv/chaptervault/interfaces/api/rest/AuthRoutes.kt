package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.AuthApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.authRoutes(auth: AuthApi) {
    routing {
        post("/auth/login") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
        post("/auth/logout") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
        get("/auth/me") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
    }
}
