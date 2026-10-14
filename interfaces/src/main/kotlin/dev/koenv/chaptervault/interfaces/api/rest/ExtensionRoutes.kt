package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.toDto
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import dev.koenv.chaptervault.shared.result.AppError
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.extensionRoutes(loaderService: ExtensionManager) {
    get("/extensions") {
        call.requirePermission(Permission.MANAGE_EXTENSIONS) ?: return@get
        call.respond(HttpStatusCode.OK, loaderService.listAll().map { it.toDto() })
    }

    get("/extensions/{id}") {
        call.requirePermission(Permission.MANAGE_EXTENSIONS) ?: return@get
        val id = call.parameters["id"]!!
        val entry = loaderService.findById(id)
        if (entry == null) {
            call.respondError(AppError.NotFound("Extension", id))
            return@get
        }
        call.respond(HttpStatusCode.OK, entry.toDto())
    }

    post("/extensions/{id}/enable") {
        call.requirePermission(Permission.MANAGE_EXTENSIONS) ?: return@post
        val id = call.parameters["id"]!!
        if (loaderService.findById(id) == null) {
            call.respondError(AppError.NotFound("Extension", id))
            return@post
        }
        loaderService.enable(id)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/extensions/{id}/disable") {
        call.requirePermission(Permission.MANAGE_EXTENSIONS) ?: return@post
        val id = call.parameters["id"]!!
        if (loaderService.findById(id) == null) {
            call.respondError(AppError.NotFound("Extension", id))
            return@post
        }
        loaderService.disable(id)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/extensions/{id}/reload") {
        call.requirePermission(Permission.MANAGE_EXTENSIONS) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respondBadRequest("Missing extension id")
        loaderService.findById(id) ?: return@post call.respondError(AppError.NotFound("Extension", id))
        loaderService.reload(id)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/extensions/{id}/unload") {
        call.requirePermission(Permission.MANAGE_EXTENSIONS) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respondBadRequest("Missing extension id")
        loaderService.findById(id) ?: return@post call.respondError(AppError.NotFound("Extension", id))
        loaderService.unload(id)
        call.respond(HttpStatusCode.NoContent)
    }
}
