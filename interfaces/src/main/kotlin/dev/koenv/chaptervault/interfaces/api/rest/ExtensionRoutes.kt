package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.toDto
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.AppError
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.extensionRoutes(loaderService: ExtensionLoaderService) {
    get("/extensions") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        call.respond(HttpStatusCode.OK, loaderService.listAll().map { it.toDto() })
    }

    get("/extensions/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        val id = call.parameters["id"]!!
        val entry = loaderService.findById(id)
        if (entry == null) {
            call.respondError(AppError.NotFound("Extension", id))
            return@get
        }
        call.respond(HttpStatusCode.OK, entry.toDto())
    }

    post("/extensions/{id}/enable") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id = call.parameters["id"]!!
        if (loaderService.findById(id) == null) {
            call.respondError(AppError.NotFound("Extension", id))
            return@post
        }
        loaderService.enable(id)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/extensions/{id}/disable") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id = call.parameters["id"]!!
        if (loaderService.findById(id) == null) {
            call.respondError(AppError.NotFound("Extension", id))
            return@post
        }
        loaderService.disable(id)
        call.respond(HttpStatusCode.NoContent)
    }
}
