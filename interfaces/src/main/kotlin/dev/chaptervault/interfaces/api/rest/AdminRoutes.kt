package dev.chaptervault.interfaces.api.rest

import dev.chaptervault.kernel.extension.Capability
import dev.chaptervault.kernel.extension.ExtensionRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.adminRoutes(registry: ExtensionRegistry) {
    val adminExtensions = registry.withCapability(Capability.CanServeAdmin)
    if (adminExtensions.isEmpty()) return

    routing {
        get("/admin") {
            call.respond(HttpStatusCode.NotImplemented, "Admin not yet implemented")
        }
    }
}
