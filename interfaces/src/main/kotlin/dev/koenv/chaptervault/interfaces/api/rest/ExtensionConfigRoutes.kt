package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.infrastructure.database.repositories.ExtensionConfigRepository
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route

fun Route.extensionConfigRoutes(
    loaderService: ExtensionManager,
    configRepository: ExtensionConfigRepository,
) {
    route("/extensions/{id}/config") {
        get {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            loaderService.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val values = configRepository.getAll(id)
            call.respond(mapOf("values" to values))
        }
        patch {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            loaderService.findById(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            val body = call.receive<Map<String, String>>()
            configRepository.setAll(id, body)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
