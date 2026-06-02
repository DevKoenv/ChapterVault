package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.ExtensionConfigApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import dev.koenv.chaptervault.shared.result.AppError
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route

fun Route.extensionConfigRoutes(
    loaderService: ExtensionManager,
    configRepository: ExtensionConfigApi,
) {
    route("/extensions/{id}/config") {
        get {
            val principal = call.principal<KtorPrincipal>()
            if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                call.respondForbidden()
                return@get
            }
            val id = call.parameters["id"] ?: return@get call.respondBadRequest("Missing extension id")
            loaderService.findById(id) ?: return@get call.respondError(AppError.NotFound("Extension", id))
            val values = configRepository.getAll(id)
            call.respond(mapOf("values" to values))
        }
        patch {
            val principal = call.principal<KtorPrincipal>()
            if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                call.respondForbidden()
                return@patch
            }
            val id = call.parameters["id"] ?: return@patch call.respondBadRequest("Missing extension id")
            loaderService.findById(id) ?: return@patch call.respondError(AppError.NotFound("Extension", id))
            val body = call.receive<Map<String, String>>()
            configRepository.setAll(id, body)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
