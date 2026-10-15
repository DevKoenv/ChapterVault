package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.StorageApi
import dev.koenv.chaptervault.kernel.api.UpdateInfoApi
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.shared.result.Result
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

@Serializable
data class StorageUsageDto(
    val libraryBytes: Long,
    val thumbnailsBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
    val minFreeBytes: Long,
)

fun Route.systemRoutes(
    updates: UpdateInfoApi,
    storage: StorageApi,
) {
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

    get("/system/storage") {
        call.requirePermission(Permission.MANAGE_SERVER) ?: return@get
        when (val result = storage.storageUsage()) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    with(result.value) { StorageUsageDto(libraryBytes, thumbnailsBytes, freeBytes, totalBytes, minFreeBytes) },
                )
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
