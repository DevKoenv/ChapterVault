package dev.chaptervault.interfaces.api.opds

import dev.chaptervault.kernel.extension.Capability
import dev.chaptervault.kernel.extension.ExtensionRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.opdsRoutes(registry: ExtensionRegistry) {
    val opdsExtensions = registry.withCapability(Capability.CanServeOpds)
    if (opdsExtensions.isEmpty()) return

    routing {
        get("/opds") {
            call.respond(HttpStatusCode.NotImplemented, "OPDS not yet implemented")
        }
    }
}
