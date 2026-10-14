package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.UpdateInfoApi
import dev.koenv.chaptervault.kernel.auth.Permission
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

fun Route.systemRoutes(updates: UpdateInfoApi) {
    get("/system/updates") {
        call.requirePermission(Permission.MANAGE_SERVER) ?: return@get
        val info = updates.getUpdateInfo()
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
