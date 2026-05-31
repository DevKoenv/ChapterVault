package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.extensionReloadRoutes(loaderService: ExtensionManager) {
    post("/extensions/{id}/reload") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (loaderService.findById(id) == null) return@post call.respond(HttpStatusCode.NotFound)
        loaderService.reload(id)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/extensions/{id}/unload") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (loaderService.findById(id) == null) return@post call.respond(HttpStatusCode.NotFound)
        loaderService.unload(id)
        call.respond(HttpStatusCode.NoContent)
    }
}
