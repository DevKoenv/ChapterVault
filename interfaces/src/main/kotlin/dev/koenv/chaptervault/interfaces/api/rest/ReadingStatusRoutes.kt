package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import kotlinx.serialization.Serializable

@Serializable
private data class SetReadingStatusRequest(
    val status: String,
)

fun Route.readingStatusRoutes(readingStatusApi: ReadingStatusApi) {
    patch("/library/series/{id}/status") {
        val principal = call.requirePermission(Permission.WRITE_PROGRESS) ?: return@patch
        val seriesId = call.idParam("id", "series") ?: return@patch
        val request = call.receiveOrBadRequest<SetReadingStatusRequest>() ?: return@patch
        val status =
            runCatching { ReadingStatus.valueOf(request.status) }.getOrNull()
                ?: run {
                    call.respondBadRequest(
                        "Invalid status '${request.status}'. Valid values: ${ReadingStatus.entries.joinToString { it.name }}",
                    )
                    return@patch
                }
        when (val result = readingStatusApi.setStatus(principal.user.id, seriesId, status)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/library/series/{id}/status") {
        val principal = call.requirePermission(Permission.WRITE_PROGRESS) ?: return@delete
        val seriesId = call.idParam("id", "series") ?: return@delete
        when (val result = readingStatusApi.clearStatus(principal.user.id, seriesId)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
