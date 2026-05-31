package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.infrastructure.database.repositories.ExtensionRegistryRepository
import dev.koenv.chaptervault.kernel.auth.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

fun Route.extensionRegistryRoutes(registryRepo: ExtensionRegistryRepository) {
    route("/extensions/registries") {
        get {
            val principal = call.principal<KtorPrincipal>()
            if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                call.respondForbidden()
                return@get
            }
            call.respond(registryRepo.list().map { it.toDto() })
        }
        post {
            val principal = call.principal<KtorPrincipal>()
            if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                call.respondForbidden()
                return@post
            }
            val body = call.receive<CreateRegistryRequest>()
            val created = registryRepo.create(body.name, body.url)
            call.respond(HttpStatusCode.Created, created.toDto())
        }
        route("/{id}") {
            patch {
                val principal = call.principal<KtorPrincipal>()
                if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                    call.respondForbidden()
                    return@patch
                }
                val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
                registryRepo.findById(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
                val body = call.receive<PatchRegistryRequest>()
                body.enabled?.let { registryRepo.setEnabled(id, it) }
                call.respond(HttpStatusCode.NoContent)
            }
            delete {
                val principal = call.principal<KtorPrincipal>()
                if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
                    call.respondForbidden()
                    return@delete
                }
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                registryRepo.findById(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                registryRepo.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

@Serializable private data class CreateRegistryRequest(val name: String, val url: String)
@Serializable private data class PatchRegistryRequest(val enabled: Boolean? = null)

private fun dev.koenv.chaptervault.infrastructure.database.repositories.ExtensionRegistryRecord.toDto() =
    mapOf("id" to id, "name" to name, "url" to url, "enabled" to enabled, "createdAt" to createdAt.toString())
