package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.libraryRoutes(
    libraryRead: LibraryReadApi,
    libraryCommand: LibraryCommandApi,
) {
    routing {
        get("/library/series") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
        get("/library/series/{id}") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
        post("/library/series") {
            call.respond(HttpStatusCode.NotImplemented, "Not yet implemented")
        }
    }
}
