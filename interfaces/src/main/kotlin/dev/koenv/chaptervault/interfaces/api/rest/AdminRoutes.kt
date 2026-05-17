package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.adminRoutes(registry: ExtensionRegistry) {
    val adminExtensions = registry.withCapability(Capability.CanServeAdmin)
    if (adminExtensions.isEmpty()) return

    get("/admin") {
        call.respond(HttpStatusCode.NotImplemented, "Admin not yet implemented")
    }
}
