package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.infrastructure.update.UpdateCheckerService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class UpdateInfoDto(
    val currentVersion: String,
    val latestVersion: String?,
    val releaseUrl: String?,
    val updateAvailable: Boolean,
)

fun Route.systemRoutes(updateCheckerService: UpdateCheckerService) {
    get("/system/updates") {
        val info = updateCheckerService.getUpdateInfo()
        call.respond(
            HttpStatusCode.OK,
            UpdateInfoDto(
                currentVersion = info.currentVersion,
                latestVersion = info.latestVersion,
                releaseUrl = info.releaseUrl,
                updateAvailable = info.updateAvailable,
            ),
        )
    }
}
