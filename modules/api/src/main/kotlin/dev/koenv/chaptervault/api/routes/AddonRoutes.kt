package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.addon.AddonActionResponse
import dev.koenv.chaptervault.api.models.addon.AddonDetailResponse
import dev.koenv.chaptervault.api.models.addon.AddonDto
import dev.koenv.chaptervault.api.models.addon.AddonErrorDto
import dev.koenv.chaptervault.api.models.addon.AddonAllErrorsResponse
import dev.koenv.chaptervault.api.models.addon.AddonErrorListResponse
import dev.koenv.chaptervault.api.models.addon.AddonErrorsEntry
import dev.koenv.chaptervault.api.models.addon.AddonListResponse
import dev.koenv.chaptervault.core.addon.AddonError
import dev.koenv.chaptervault.core.addon.AddonInfo
import dev.koenv.chaptervault.core.addon.AddonRegistry
import dev.koenv.chaptervault.core.addon.AddonState
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.addonRoutes(addonRegistry: AddonRegistry) {
    route("/api/v1/admin/addons") {
        get {
            val addons = addonRegistry.getAllAddons().map { it.toDto() }
            call.respond(AddonListResponse(addons))
        }

        get("errors") {
            val entries = addonRegistry.getAllAddons()
                .filter { it.errors.isNotEmpty() }
                .map { AddonErrorsEntry(addonId = it.id, addonName = it.name, errors = it.errors.map { e -> e.toDto() }) }
            call.respond(AddonAllErrorsResponse(entries))
        }

        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val info = addonRegistry.getAddon(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val errors = addonRegistry.getErrors(id).map { it.toDto() }
            call.respond(AddonDetailResponse(addon = info.toDto(), errors = errors))
        }

        post("{id}/enable") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val info = addonRegistry.getAddon(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            if (info.state != AddonState.DISABLED) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    AddonActionResponse(id, info.state.name, "Addon is not DISABLED")
                )
            }
            addonRegistry.enableAddon(id)
            val updated = addonRegistry.getAddon(id)!!
            call.respond(AddonActionResponse(id, updated.state.name, "Addon enabled"))
        }

        post("{id}/disable") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val info = addonRegistry.getAddon(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            if (info.state != AddonState.ENABLED) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    AddonActionResponse(id, info.state.name, "Addon is not ENABLED")
                )
            }
            addonRegistry.disableAddon(id)
            val updated = addonRegistry.getAddon(id)!!
            call.respond(AddonActionResponse(id, updated.state.name, "Addon disabled"))
        }

        post("{id}/reload") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            addonRegistry.getAddon(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            addonRegistry.reloadAddon(id)
            val updated = addonRegistry.getAddon(id)!!
            call.respond(AddonActionResponse(id, updated.state.name, "Addon reloaded"))
        }

        get("{id}/errors") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            addonRegistry.getAddon(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val errors = addonRegistry.getErrors(id).map { it.toDto() }
            call.respond(AddonErrorListResponse(errors))
        }

        delete("{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            addonRegistry.getAddon(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
            addonRegistry.removeAddon(id)
            call.respond(AddonActionResponse(id, "REMOVED", "Addon removed"))
        }
    }
}

private fun AddonInfo.toDto() = AddonDto(
    id = id,
    name = name,
    version = version,
    apiVersion = apiVersion,
    state = state.name,
    connectorIds = connectorIds,
    depends = depends,
    optionalDepends = optionalDepends
)

private fun AddonError.toDto() = AddonErrorDto(
    phase = phase,
    message = message,
    stackTrace = stackTrace,
    occurredAt = occurredAt.toString()
)
