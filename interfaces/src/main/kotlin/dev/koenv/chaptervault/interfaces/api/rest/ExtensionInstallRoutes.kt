package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.infrastructure.extensions.ExtensionRegistryService
import dev.koenv.chaptervault.kernel.auth.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

fun Route.extensionInstallRoutes(registryService: ExtensionRegistryService) {
    route("/extensions/registry") {
        get {
            val principal = call.principal<KtorPrincipal>()
            if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                call.respondForbidden()
                return@get
            }
            val all = registryService.listAll()
            call.respond(all.map {
                mapOf(
                    "id" to it.entry.id,
                    "name" to it.entry.name,
                    "version" to it.entry.version,
                    "registry" to it.registryName,
                    "description" to it.entry.description,
                    "conflicting" to it.conflicting,
                )
            })
        }
        post("/refresh") {
            val principal = call.principal<KtorPrincipal>()
            if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                call.respondForbidden()
                return@post
            }
            registryService.refresh()
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/extensions/install") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val body = call.receive<InstallRequest>()
        runCatching { registryService.install(body.extensionId) }
            .onSuccess { call.respond(HttpStatusCode.NoContent) }
            .onFailure { e -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (e.message ?: "install failed"))) }
    }
}

@Serializable private data class InstallRequest(val extensionId: String)
